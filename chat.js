/**
 * The public chat page — a small messenger.
 *
 * Two rules run through the whole file:
 *
 * **Nothing is ever written as HTML.** Every message on this page came from a stranger or from an
 * operator typing quickly, and lands via `textContent`. There is no `innerHTML` anywhere below, so
 * there is no sanitiser to get wrong.
 *
 * **The socket is a hint; HTTP is the truth.** Messages are sent by POST — that is where the
 * validation, the rate limit and the idempotency key live — and the socket only says "something
 * happened". Pub/sub replays nothing after a reconnect, so every reconnect reloads rather than
 * carrying on.
 *
 * ── The shape ────────────────────────────────────────────────────────────────────────────────
 *
 * Home is where a visitor arrives: a greeting, the people who will answer, a box to start writing
 * in, and their last few conversations. The full list and each conversation are places they *go*,
 * each with a back arrow. A visitor has at most one live conversation (the server's rule), so
 * "Write to us" is "carry on" when there is one and "start one" when there is not.
 *
 * Each reply is signed underneath: an operator by first name and face, an assistant by name, mascot
 * and an "AI" tag, the business by its own mark. A closed conversation ends with five faces
 * asking how it went, and a visitor who started typing before giving a name is asked for one in
 * the thread.
 *
 * What it does is benchmarked on Intercom's Messenger; how it looks is our own — the paper ground
 * and the serif this page has always had.
 */

const $ = (id) => document.getElementById(id);
const publicKey = decodeURIComponent(location.pathname.split('/').pop() ?? '');
const STORE = `cnct_chat_${publicKey}`;
/** When each conversation was last looked at, so the list can say which have news in them. */
const SEEN = `cnct_seen_${publicKey}`;
const FACES = '/chat-assets/faces.svg';

/**
 * The same page, in somebody else's website rather than as its own tab.
 *
 * Exactly three things change, and the stylesheet handles the first: it becomes a panel instead of
 * a centred card, it gains a close button (there is no browser tab to shut), and it tells the
 * launcher outside how many messages have arrived while nobody was looking.
 */
const params = new URLSearchParams(location.search);
const embedded = params.get('embed') === '1';
if (embedded) document.documentElement.classList.add('embed');

/**
 * **Preview mode, and the one rule that makes it safe: it never calls the API.**
 *
 * The console's widget builder shows this page in an iframe, so that what a client is adjusting is
 * the product rather than a drawing of it that drifts from it. Without this flag that would mean a
 * real boot call on every keystroke — and `GET /api/chat/public/:key` is not a read: it fires the
 * `chat opened` analytics event that the whole web funnel is counted from. A client nudging a
 * colour slider would have quietly invented a hundred conversations that nobody had.
 *
 * So in preview nothing is fetched, no session is started, no visitor row is written and nothing is
 * counted. The configuration arrives by `postMessage` from the builder instead, which also means it
 * can show *unsaved* changes — the thing a preview is for.
 */
const preview = params.get('preview') === '1';

/** Whether the panel around us is actually on screen. The launcher is the only thing that knows. */
let panelOpen = !embedded;
let unseen = 0;

/**
 * `'*'` as the target origin, deliberately. We are inside an iframe on a domain we do not know and
 * cannot be told reliably — a `host` query parameter would be whatever the URL said, which is not
 * a fact. It is safe here because of what these messages *are*: a request to close, and a count of
 * unread messages. Nothing identifying, nothing secret, nothing an eavesdropping parent could not
 * already see by looking at its own iframe. The security boundary is the other way round, and it is
 * enforced: the launcher checks `event.origin` before it reads anything we send.
 */
const toHost = (message) => {
  if (embedded && window.parent !== window) window.parent.postMessage(message, '*');
};

/**
 * **Who is signed in on the host page — asked before any history is read.**
 *
 * On a client's site the launcher knows whether the visitor is one of the site's logged-in users
 * (see `cleanIdentity` in `cnct-widget.js`). The panel asks as soon as it loads and waits for the
 * answer before it reads a single conversation, because the answer decides whose conversations
 * those are. The wait is bounded: a launcher older than this question never answers it, and the
 * page goes on anonymously after a second and a half rather than hanging.
 */
let identityKnown = false;
let settleIdentity = () => {};
const identityReady = new Promise((resolve) => {
  settleIdentity = (identity) => {
    if (identityKnown) return;
    identityKnown = true;
    resolve(identity);
  };
});
if (embedded && !preview) {
  toHost({ type: 'cnct:hello' });
  setTimeout(() => settleIdentity(null), 1500);
} else {
  settleIdentity(null);
}

/** The identity the launcher sent, if it is shaped like one. The server checks the rest. */
const validIdentity = (value) =>
  value && typeof value.userId === 'string' && typeof value.userHash === 'string' ? value : null;

window.addEventListener('message', (event) => {
  if (!embedded || preview) return;
  const data = event.data;
  if (data?.type === 'cnct:opened') {
    panelOpen = true;
    unseen = 0;
    // Whatever was on screen when it opened has now been seen.
    if (state.screen === 'chat') markSeen(state.current?.conversation);
  }
  if (data?.type === 'cnct:closed') panelOpen = false;
  /**
   * The host's `CnctChat.showNewMessage(text)` and `showConversations()`, relayed by the launcher.
   * Only from the parent page — the launcher waits for `cnct:ready` before sending, so by the time
   * these arrive the page has booted and knows which screens exist.
   */
  if (event.source !== window.parent) return;
  if (data?.type === 'cnct:compose') compose(typeof data.text === 'string' ? data.text : '');
  if (data?.type === 'cnct:navigate') navigate(data.to);
  if (data?.type === 'cnct:identity') {
    const identity = validIdentity(data.identity);
    // The first answer is the one boot waits for; a later one is the site signing somebody in.
    if (!identityKnown) settleIdentity(identity);
    else if (identity) void reidentify(identity);
  }
  if (data?.type === 'cnct:shutdown') shutdown();
});

/**
 * What the page looks like before the server has said otherwise — and what it keeps looking like if
 * the boot call fails on a working network. Kept in step with `widgetConfigSchema` by the test that
 * asserts the two agree.
 */
const DEFAULT_WIDGET = {
  brand: {
    color: '#b52d93',
    appearance: 'light',
    businessName: null,
    avatarUrl: null,
    language: 'auto',
    showBranding: true,
  },
  launcher: {
    side: 'right',
    shape: 'circle',
    size: 'medium',
    label: 'Chat with us',
    offset: 20,
    hideOnMobile: false,
    showReplyPreview: true,
  },
  home: {
    enabled: true,
    heading: 'Hello.',
    tagline: 'Ask us anything.',
    questions: [],
    showTeam: true,
  },
  chat: {
    allowAttachments: true,
    allowEmoji: true,
    allowEndConversation: false,
    start: 'details',
    askForRating: true,
  },
};

/** Section by section over the defaults, exactly as the server merges — never a shallow spread. */
const mergeWidget = (widget) => ({
  brand: { ...DEFAULT_WIDGET.brand, ...(widget?.brand ?? {}) },
  launcher: { ...DEFAULT_WIDGET.launcher, ...(widget?.launcher ?? {}) },
  home: { ...DEFAULT_WIDGET.home, ...(widget?.home ?? {}) },
  chat: { ...DEFAULT_WIDGET.chat, ...(widget?.chat ?? {}) },
});

/**
 * The eight a person actually sends a business. A full picker is a dependency, a search index and a
 * megabyte of images on a page a stranger loads over their phone data — and every phone already has
 * the real keyboard one tap away, which is why this is a convenience for desktops rather than a
 * feature anybody needs.
 */
/**
 * The emoji the tray opens on: what people actually send a business. There at once, before the rest
 * has loaded, and topping up the row of the visitor's own until they have sent eight — see `Emoji`.
 */
const QUICK_EMOJI = ['👍', '🙏', '😊', '👌', '🎉', '❤️', '😅', '🤔'];

/**
 * The five faces, worst to best, and the score each one stores. Drawn from the product's face set
 * (Fluent Emoji, generated into `faces.svg` — see `web/components/rating-face.tsx`) rather than the
 * phone's own emoji, so the face a customer taps is exactly the face an operator reads. Words
 * beside them because a face is read differently across cultures and a screen reader reads none of
 * them well.
 */
const RATINGS = [1, 2, 3, 4, 5];

/**
 * The default tagline, and the one before it. The old one promised "a few minutes" whatever the
 * hour; a builder saved without touching it still holds it, and it is still ours to replace — Home
 * now says how fast replies really come, right underneath.
 */
const OUR_TAGLINES = [
  'Ask us anything.',
  'Ask us anything — we usually reply in a few minutes.',
];

/**
 * **Every word the widget says, in English and Arabic.**
 *
 * One table per language with the same keys — `t()` falls back to English for a key a translation
 * has not caught up with, so a missing line is an English word rather than a blank. Only the
 * widget's own words live here: the greeting, tagline and questions are the client's, in whatever
 * language they wrote them, and are shown as written. (The two defaults a client has never changed
 * are the exception — an untouched "Hello." is ours, so it is said in the visitor's language.)
 *
 * `{name}` marks where a value goes. Nothing in here is ever HTML; every string lands through
 * `textContent`, exactly as the rest of the page does.
 */
const STRINGS = {
  en: {
    connecting: 'Connecting…',
    closeChat: 'Close chat',
    back: 'Back',
    unavailableTitle: 'This chat is not available',
    linkInvalid: 'This chat link is not valid',
    linkInvalidBody: 'Check the link you were given, or get in touch another way.',
    chatUnavailable: 'Chat is unavailable',
    tryAgainSoon: 'Please try again in a moment.',
    notTakingChats: '{business} is not taking chats right now',
    notTakingChatsBody: 'Please try again later, or call us instead.',
    genericError: 'Something went wrong. Please try again.',
    poweredBy: 'Powered by',
    defaultHeading: 'Hello.',
    defaultTagline: 'Ask us anything.',
    writeToUs: 'Write to us…',
    carryOn: 'Carry on where you left off…',
    yourConversations: 'Your conversations',
    seeAll: 'See all {count}',
    oneHere: '{name} is here to help',
    manyHere: '{names} and {last} are here to help',
    listSeparator: ', ',
    replyUnder1: 'Usually replies in under a minute',
    replyFew: 'Usually replies in a few minutes',
    reply20: 'Usually replies in under 20 minutes',
    replyHour: 'Usually replies in under an hour',
    replyHours: 'Usually replies in a few hours',
    replyDay: 'Usually replies within a day',
    replyUnknown: 'The team will reply here as soon as they can',
    assistantNow: 'Our assistant replies straight away',
    backToday: 'The team is back today at {time}',
    backTomorrow: 'The team is back tomorrow at {time}',
    backOn: 'The team is back {day} at {time}',
    backAssistant: '{back} · our assistant can help now',
    nothingYet: 'Nothing here yet',
    keptHere: 'Conversations with the team will be kept here.',
    startNew: 'Start a new conversation',
    carryOnOpen: 'Carry on the open conversation',
    forgetMe: 'Forget me on this device',
    open: 'Open',
    you: 'You',
    sentAFile: 'Sent a file',
    yourName: 'Your name',
    namePlaceholder: 'What should we call you?',
    phoneNumber: 'Phone number',
    optional: 'optional',
    phoneHint: 'So we can pick up where we left off if you have called us before.',
    startChatting: 'Start chatting',
    starting: 'Starting…',
    legal: "Your messages are kept so the team can answer you and look back at what was agreed. Don't send card numbers or passwords.",
    tooManyChats: 'That is a lot of chats at once. Give it a minute and try again.',
    endChat: 'End chat',
    endConfirm: 'End this chat? You can always start a new one.',
    keepChatting: 'Keep chatting',
    message: 'Message',
    writeMessage: 'Write a message…',
    addEmoji: 'Add an emoji',
    emoji: 'Emoji',
    searchEmoji: 'Search emoji',
    noEmoji: 'No emoji for “{query}”',
    emojiLoading: 'Loading the rest…',
    emojiFailed: 'The rest of the emoji did not load. Close and open this again to retry.',
    emojiRecent: 'Frequently used',
    emojiSmileys: 'Smileys & emotion',
    emojiPeople: 'People & body',
    emojiNature: 'Animals & nature',
    emojiFood: 'Food & drink',
    emojiTravel: 'Travel & places',
    emojiActivities: 'Activities',
    emojiObjects: 'Objects',
    emojiSymbols: 'Symbols',
    emojiFlags: 'Flags',
    send: 'Send',
    conversation: 'Conversation',
    teamCanHelp: 'The team can step in at any time',
    withPerson: 'Someone from the team is with you',
    aiAssistant: 'AI assistant',
    ended: 'Conversation ended {when}',
    endedToday: 'today',
    endedYesterday: 'yesterday',
    endedOn: 'on {date}',
    endedDivider: 'This conversation has ended',
    today: 'Today',
    yesterday: 'Yesterday',
    justNow: 'Just now',
    minutesShort: '{n}m',
    hoursShort: '{n}h',
    sending: 'Sending…',
    notSent: 'Not sent — tap to try again',
    seen: 'Seen',
    attachment: 'Attachment',
    couldNotLoad: '{name} could not be loaded',
    slowDown: 'Slow down a moment — that is a lot of messages.',
    ratePrompt: 'How did we do?',
    rated: 'Thank you — that goes straight to the team.',
    tellUsMore: 'Anything you would like to add? (optional)',
    skip: 'No thanks',
    rating1: 'Terrible',
    rating2: 'Bad',
    rating3: 'OK',
    rating4: 'Good',
    rating5: 'Great',
    detailsTitle: 'Who are we talking to?',
    detailsBody: 'Leave your name so the team knows who they are chatting with.',
    detailsPhone: 'Phone number (optional)',
    detailsSave: 'Save',
    detailsThanks: 'Thanks, {name} — the team will see your name.',
    attach: 'Attach a file',
    dropHere: 'Drop files to send them',
    tooBig: '{name} is over 50 MB.',
    tooMany: 'Up to 10 files at a time.',
    uploadFailed: '{name} could not be sent.',
    removeFile: 'Remove {name}',
    uploading: 'Uploading…',
    choices: 'Choices',
    quoteYou: 'You',
    quoteEarlier: 'Earlier message',
    quoteIMAGE: 'Photo',
    quoteVIDEO: 'Video',
    quoteAUDIO: 'Voice message',
    quoteSTICKER: 'Sticker',
    quoteDOCUMENT: 'File',
    showQuoted: 'Show the message this replies to',
    chooseOrWrite: 'Choose one, or type…',
    answerName: 'Type your name…',
    answerNumber: 'Type a number…',
    answerEmail: 'Type your email address…',
    answerPhone: 'Type your phone number…',
    answerDate: 'Type a date…',
    openDocument: 'Open {name}',
  },
  ar: {
    connecting: 'جارٍ الاتصال…',
    closeChat: 'إغلاق المحادثة',
    back: 'رجوع',
    unavailableTitle: 'هذه المحادثة غير متاحة',
    linkInvalid: 'رابط المحادثة هذا غير صالح',
    linkInvalidBody: 'تحقّق من الرابط الذي وصلك، أو تواصل معنا بطريقة أخرى.',
    chatUnavailable: 'المحادثة غير متاحة',
    tryAgainSoon: 'يُرجى المحاولة بعد قليل.',
    notTakingChats: '{business} لا يستقبل المحادثات الآن',
    notTakingChatsBody: 'يُرجى المحاولة لاحقاً، أو الاتصال بنا.',
    genericError: 'حدث خطأ ما. يُرجى المحاولة مجدداً.',
    poweredBy: 'مدعوم من',
    defaultHeading: 'أهلاً.',
    defaultTagline: 'اسألنا عن أي شيء.',
    writeToUs: 'اكتب لنا…',
    carryOn: 'أكمل من حيث توقفت…',
    yourConversations: 'محادثاتك',
    seeAll: 'عرض الكل ({count})',
    oneHere: '{name} هنا للمساعدة',
    manyHere: '{names} و{last} هنا للمساعدة',
    listSeparator: '، ',
    replyUnder1: 'يردّ عادةً خلال أقل من دقيقة',
    replyFew: 'يردّ عادةً خلال دقائق',
    reply20: 'يردّ عادةً خلال أقل من 20 دقيقة',
    replyHour: 'يردّ عادةً خلال أقل من ساعة',
    replyHours: 'يردّ عادةً خلال ساعات',
    replyDay: 'يردّ عادةً خلال يوم',
    replyUnknown: 'سيردّ الفريق هنا في أقرب وقت',
    assistantNow: 'يردّ مساعدنا فوراً',
    backToday: 'يعود الفريق اليوم الساعة {time}',
    backTomorrow: 'يعود الفريق غداً الساعة {time}',
    backOn: 'يعود الفريق يوم {day} الساعة {time}',
    backAssistant: '{back} · ومساعدنا جاهز الآن',
    nothingYet: 'لا شيء هنا بعد',
    keptHere: 'ستُحفظ محادثاتك مع الفريق هنا.',
    startNew: 'ابدأ محادثة جديدة',
    carryOnOpen: 'أكمل المحادثة المفتوحة',
    forgetMe: 'انسَني على هذا الجهاز',
    open: 'مفتوحة',
    you: 'أنت',
    sentAFile: 'أرسل ملفاً',
    yourName: 'اسمك',
    namePlaceholder: 'بماذا نناديك؟',
    phoneNumber: 'رقم الهاتف',
    optional: 'اختياري',
    phoneHint: 'لنكمل من حيث توقفنا إن كنت قد اتصلت بنا من قبل.',
    startChatting: 'ابدأ المحادثة',
    starting: 'جارٍ البدء…',
    legal: 'نحتفظ برسائلك ليتمكن الفريق من الرد عليك والرجوع إلى ما اتُّفق عليه. لا ترسل أرقام البطاقات أو كلمات المرور.',
    tooManyChats: 'محادثات كثيرة في وقت واحد. انتظر دقيقة ثم حاول مجدداً.',
    endChat: 'إنهاء المحادثة',
    endConfirm: 'هل تريد إنهاء هذه المحادثة؟ يمكنك دائماً بدء محادثة جديدة.',
    keepChatting: 'متابعة المحادثة',
    message: 'رسالة',
    writeMessage: 'اكتب رسالة…',
    addEmoji: 'أضف رمزاً تعبيرياً',
    emoji: 'رموز تعبيرية',
    searchEmoji: 'ابحث عن رمز تعبيري',
    noEmoji: 'لا توجد رموز تعبيرية لـ «{query}»',
    emojiLoading: 'جارٍ تحميل البقية…',
    emojiFailed: 'تعذّر تحميل بقية الرموز التعبيرية. أغلق هذا وافتحه مجدداً للمحاولة مرة أخرى.',
    emojiRecent: 'الأكثر استخداماً',
    emojiSmileys: 'الوجوه والمشاعر',
    emojiPeople: 'الأشخاص والجسم',
    emojiNature: 'الحيوانات والطبيعة',
    emojiFood: 'الطعام والشراب',
    emojiTravel: 'السفر والأماكن',
    emojiActivities: 'الأنشطة',
    emojiObjects: 'الأشياء',
    emojiSymbols: 'الرموز',
    emojiFlags: 'الأعلام',
    send: 'إرسال',
    conversation: 'المحادثة',
    teamCanHelp: 'يمكن للفريق التدخّل في أي وقت',
    withPerson: 'أحد أعضاء الفريق معك',
    aiAssistant: 'مساعد ذكي',
    ended: 'انتهت المحادثة {when}',
    endedToday: 'اليوم',
    endedYesterday: 'أمس',
    endedOn: 'في {date}',
    endedDivider: 'انتهت هذه المحادثة',
    today: 'اليوم',
    yesterday: 'أمس',
    justNow: 'الآن',
    minutesShort: '{n} د',
    hoursShort: '{n} س',
    sending: 'جارٍ الإرسال…',
    notSent: 'لم تُرسل، اضغط للمحاولة مجدداً',
    seen: 'شوهدت',
    attachment: 'مرفق',
    couldNotLoad: 'تعذّر تحميل {name}',
    slowDown: 'تمهّل قليلاً، هذه رسائل كثيرة.',
    ratePrompt: 'كيف كانت تجربتك معنا؟',
    rated: 'شكراً لك، سيصل رأيك إلى الفريق مباشرةً.',
    tellUsMore: 'هل تودّ إضافة شيء؟ (اختياري)',
    skip: 'لا، شكراً',
    rating1: 'سيئة جداً',
    rating2: 'سيئة',
    rating3: 'مقبولة',
    rating4: 'جيدة',
    rating5: 'ممتازة',
    detailsTitle: 'مع من نتحدث؟',
    detailsBody: 'اترك اسمك ليعرف الفريق مع من يتحدث.',
    detailsPhone: 'رقم الهاتف (اختياري)',
    detailsSave: 'حفظ',
    detailsThanks: 'شكراً {name}، سيرى الفريق اسمك.',
    attach: 'إرفاق ملف',
    dropHere: 'أفلت الملفات لإرسالها',
    tooBig: '{name} أكبر من 50 ميغابايت.',
    tooMany: 'حتى 10 ملفات في المرة الواحدة.',
    uploadFailed: 'تعذّر إرسال {name}.',
    removeFile: 'إزالة {name}',
    uploading: 'جارٍ الرفع…',
    choices: 'الخيارات',
    quoteYou: 'أنت',
    quoteEarlier: 'رسالة سابقة',
    quoteIMAGE: 'صورة',
    quoteVIDEO: 'فيديو',
    quoteAUDIO: 'رسالة صوتية',
    quoteSTICKER: 'ملصق',
    quoteDOCUMENT: 'ملف',
    showQuoted: 'اعرض الرسالة التي يرد عليها',
    chooseOrWrite: 'اختر خياراً، أو اكتب…',
    answerName: 'اكتب اسمك…',
    answerNumber: 'اكتب رقماً…',
    answerEmail: 'اكتب بريدك الإلكتروني…',
    answerPhone: 'اكتب رقم هاتفك…',
    answerDate: 'اكتب تاريخاً…',
    openDocument: 'فتح {name}',
  },
};

/** The widget's language for this visitor, decided in `applyWidget`. */
let lang = 'en';

const t = (key, vars = {}) => {
  let text = (STRINGS[lang] ?? STRINGS.en)[key] ?? STRINGS.en[key] ?? key;
  for (const [name, value] of Object.entries(vars)) text = text.split(`{${name}}`).join(String(value));
  return text;
};

/** Latin digits in Arabic, as Gulf sites mostly write them; the visitor's own locale otherwise. */
const locale = () => (lang === 'ar' ? 'ar-u-nu-latn' : undefined);

/**
 * `auto` follows the visitor's own browser — the first of their languages we speak — so one install
 * serves an Arabic phone in Arabic and an English laptop in English. A client who pinned one gets it.
 */
const pickLanguage = (setting) => {
  if (setting === 'en' || setting === 'ar') return setting;
  const wanted = navigator.languages?.length ? navigator.languages : [navigator.language];
  for (const tag of wanted) {
    const base = String(tag || '').toLowerCase().split('-')[0];
    if (base === 'ar' || base === 'en') return base;
  }
  return 'en';
};

/** The words written into the page itself, marked with `data-i18n*`, in the current language. */
function translateStatic() {
  for (const node of document.querySelectorAll('[data-i18n]')) node.textContent = t(node.dataset.i18n);
  for (const node of document.querySelectorAll('[data-i18n-placeholder]')) {
    node.placeholder = t(node.dataset.i18nPlaceholder);
  }
  for (const node of document.querySelectorAll('[data-i18n-label]')) {
    node.setAttribute('aria-label', t(node.dataset.i18nLabel));
  }
}

const storage = {
  get(key) {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, value);
    } catch {
      /* Private mode, or a browser that blocks storage in a third-party frame. */
    }
  },
  remove(key) {
    try {
      localStorage.removeItem(key);
    } catch {
      /* As above. */
    }
  },
};

const state = {
  token: storage.get(STORE),
  business: '',
  greeting: '',
  requirePhone: false,
  widget: DEFAULT_WIDGET,
  /** A few of the people who will answer, for Home. First names and faces only. */
  team: [],
  /** Whether the team is in, when it is back, and how fast it replies — see `availabilityLine`. */
  availability: null,
  /** `{ displayName, anonymous, identified }` once a session exists; null for a stranger. */
  visitor: null,
  /** Whether the host page signed this visitor in on this load (`/identify` succeeded). */
  signedIn: false,
  /** The Messages list, newest first. */
  conversations: [],
  /**
   * What the conversation screen shows: `{ conversation, messages }`. A null `conversation` is a
   * new one with nothing sent yet — the composer, with nobody to send it to until the first message
   * starts the session.
   */
  current: null,
  /** Which tab a conversation's back arrow returns to. */
  tab: 'home',
  screen: 'boot',
  /** A suggested question clicked on Home, sent as the first message once a session exists. */
  opener: '',
  /** Text the host asked to put in the box (`showNewMessage`), placed once the composer exists. */
  prefill: '',
  socket: null,
  /** Messages posted but not yet acknowledged, keyed by clientKey. */
  pending: new Map(),
  /**
   * Files picked, dropped or pasted, on their way up or waiting to be sent: `{ key, file, name,
   * image, url, progress, status: 'uploading' | 'done' | 'failed', attachment, xhr, node }`.
   * They always belong to the live conversation — the only one a visitor can write in.
   */
  uploads: [],
  /** The session being started by whichever came first — a message or a file. See `ensureSession`. */
  starting: null,
  /** Per conversation, the newest reply already reported as read, so each is reported once. */
  reported: {},
  typingTimer: null,
  /**
   * An assistant's reply while it is still being written: `{ id, body, status }`, or null. Only an
   * agent that streams sends these (`chat.draft`). Drawn where the dots would be, and dropped the
   * moment the finished message arrives, which replaces it.
   */
  draft: null,
  lastTypingSentAt: 0,
  reconnectAttempt: 0,
  /** Where each conversation's rating card is: faces, the comment box, or thanked. */
  ratingStage: {},
  ratingDraft: {},
  /** The conversation whose comment box should take the caret when it next appears. */
  ratingFocus: null,
  /** The name card's half-typed values, kept so a re-render never eats what they were writing. */
  detailsDraft: { name: '', phone: '' },
  /** The name they just gave, for a "thanks" in place of the card until they move on. */
  justNamed: '',
  /**
   * Bumped on every move to another conversation. A transcript that arrives after the visitor has
   * already gone somewhere else checks it and is dropped, rather than painting yesterday's chat
   * over the one they just opened.
   */
  navigation: 0,
  /**
   * What is already on screen, so only what is new animates in. The thread is redrawn whole on
   * every message, and an entrance animation on every group made the entire conversation blink
   * each time somebody said anything. Keyed by the visitor's own `clientKey` where there is one,
   * so their message does not animate twice — once as it is sent and again when the server's copy
   * replaces it.
   */
  drawn: { for: null, keys: new Set(), cards: new Set() },
  seen: (() => {
    try {
      return JSON.parse(storage.get(SEEN) || '{}') || {};
    } catch {
      return {};
    }
  })(),
};

const show = (id) => {
  state.screen = id;
  for (const section of document.querySelectorAll('.app > .screen')) {
    section.classList.toggle('hidden', section.id !== id);
  }
  // The list's way back is to Home, and there is no Home to go back to when the client turned it off.
  $('messagesBack').classList.toggle('hidden', !state.widget.home.enabled);
};

const api = async (path, options = {}) => {
  const response = await fetch(path, {
    ...options,
    headers: {
      /* Only when there is something to describe. The server refuses a request that says JSON and
         sends nothing — "End chat" is a POST with no body, and it was answered 400 every time, so
         the conversation it was meant to close stayed open in the console. */
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(state.token ? { 'x-chat-token': state.token } : {}),
      ...options.headers,
    },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(
      typeof data.error === 'string' ? data.error : t('genericError'),
    );
    error.status = response.status;
    throw error;
  }
  return data;
};

const showError = (element, message) => {
  element.textContent = message;
  element.classList.remove('hidden');
};
const clearError = (element) => element.classList.add('hidden');

const el = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
};

const icon = (id, className) => {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('aria-hidden', 'true');
  if (className) svg.setAttribute('class', className);
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', id);
  svg.append(use);
  return svg;
};

/* ── What the client chose ────────────────────────────────────────────── */

/**
 * Which ink reads on the business's colour — the same sRGB luminance the server computes and the
 * launcher repeats. Half the palette a non-designer reaches for makes white text unreadable, and
 * the alternative is a second colour picker asking them to solve contrast by eye.
 */
const readableInk = (hex) => {
  const channel = (offset) => {
    const value = Number.parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  const luminance = 0.2126 * channel(1) + 0.7152 * channel(3) + 0.0722 * channel(5);
  return luminance > 0.55 ? '#141414' : '#ffffff';
};

/** The business's logo, or the first letter of its name on its own colour. Never an empty disc. */
const paintMark = (element, business, avatarUrl) => {
  element.replaceChildren();
  if (avatarUrl) {
    const image = document.createElement('img');
    image.src = avatarUrl;
    image.alt = '';
    /* A logo that 404s must leave the letter behind, not a broken-image icon on the one screen
       that decides whether a stranger starts a conversation. */
    image.addEventListener('error', () => {
      element.replaceChildren(document.createTextNode(initial(business)));
    });
    element.append(image);
    return;
  }
  element.textContent = initial(business);
};

const initial = (name) => (name || '?').trim().charAt(0).toUpperCase() || '?';

/* ── Faces ────────────────────────────────────────────────────────────── */

/**
 * One face, for whoever a sender is. The drawings come from a sprite generated out of the console's
 * own (see `web/test/chat-faces-sprite.test.tsx`), so the face a customer sees beside a reply is the
 * one the operator picked for themselves. Keys are checked for shape before they become an `href`;
 * the server only sends keys from its shelves, and this is the second lock on the same door.
 */
const KEY = /^[a-z]+(-[a-z]+)?$/;

function face(sender, size = 'md') {
  const node = el('span', `face ${size}`);
  if (sender?.kind === 'ai') {
    node.classList.add('ai');
    if (sender.colour && KEY.test(sender.colour)) {
      node.style.color = `var(--mascot-${sender.colour})`;
    }
    if (KEY.test(sender.mascot || '')) {
      node.append(icon(`${FACES}#mascot-${sender.mascot}`, 'mascot'));
      return node;
    }
  }
  if (sender?.kind === 'person') {
    if (sender.avatar && KEY.test(sender.avatar)) {
      node.classList.add('art');
      node.append(icon(`${FACES}#avatar-${sender.avatar}`));
      return node;
    }
    node.classList.add('letter');
    node.textContent = initial(sender.name || state.business);
    return node;
  }
  // The business itself: its logo, or its letter on its colour.
  node.classList.add('brand');
  paintMark(node, state.business, state.widget.brand.avatarUrl);
  return node;
}

/** Up to three faces, overlapping, the way a messenger shows "the team". */
function faceStack(faces, size = 'md') {
  const stack = el('span', 'stack');
  for (const one of faces.slice(0, 3)) stack.append(face(one, size));
  return stack;
}

/** Who a message is from, as one comparable string — what decides where a group starts. */
const signer = (message) => {
  if (message.side === 'you') return 'you';
  const sender = message.sender;
  if (!sender) return message.speaker === 'AI' ? 'ai:' : 'business';
  return `${sender.kind}:${sender.name ?? ''}:${sender.avatar ?? sender.mascot ?? ''}`;
};

/** The name a group is signed with. A person with no name is signed by the business. */
const signerName = (message) => {
  const sender = message.sender;
  if (sender?.name) return sender.name;
  return state.business;
};

/* ── Applying the configuration ───────────────────────────────────────── */

/**
 * Every visual decision in the configuration, applied in one pass.
 *
 * Called twice on a normal load — once with the defaults so the page is never unstyled, once with
 * the inbox's own answer — and on every keystroke in the builder's preview. So it must be
 * idempotent and must never accumulate: every list is rebuilt rather than appended to, and every
 * `hidden` is set from the value rather than toggled.
 */
function applyWidget(widget) {
  state.widget = widget;
  const root = document.documentElement;
  const color = /^#[0-9a-fA-F]{6}$/.test(widget.brand.color)
    ? widget.brand.color
    : DEFAULT_WIDGET.brand.color;
  root.style.setProperty('--brand', color);
  root.style.setProperty('--brand-ink', readableInk(color));
  /* `auto` is carried rather than dropped: the stylesheet's dark block is written as "not light",
     so the attribute only has to distinguish a deliberate light from everything else. */
  root.dataset.appearance = widget.brand.appearance;
  /**
   * The language, and with it the direction: Arabic turns the whole frame right-to-left, which the
   * stylesheet is written for with logical properties throughout. Re-read on every apply, so the
   * builder's preview switches the moment a client picks one.
   */
  lang = pickLanguage(widget.brand.language);
  root.lang = lang;
  root.dir = lang === 'ar' ? 'rtl' : 'ltr';
  translateStatic();

  const business = state.business || widget.brand.businessName || '';
  for (const id of ['homeMark', 'introMark']) paintMark($(id), business, widget.brand.avatarUrl);
  $('homeBusiness').textContent = business;

  // The two defaults nobody changed are ours, so they are said in the visitor's language.
  $('homeHeading').textContent =
    widget.home.heading === DEFAULT_WIDGET.home.heading ? t('defaultHeading') : widget.home.heading;
  $('homeHeading').dir = 'auto';
  $('homeTagline').textContent = OUR_TAGLINES.includes(widget.home.tagline)
    ? t('defaultTagline')
    : widget.home.tagline;
  $('homeTagline').dir = 'auto';
  $('homeTagline').classList.toggle('hidden', !widget.home.tagline);

  /* Rebuilt from scratch, never appended to — this runs on every keystroke in the builder. */
  const openers = $('homeQuestions');
  openers.replaceChildren();
  for (const question of widget.home.questions) {
    if (!question) continue;
    const button = el('button', 'opener');
    button.type = 'button';
    const label = el('span', '', question);
    label.dir = 'auto';
    button.append(label, icon('#c-chevron', 'chev'));
    button.addEventListener('click', () => startNew(question));
    openers.append(button);
  }
  openers.classList.toggle('hidden', !widget.home.questions.some(Boolean));

  $('emojiButton').classList.toggle('hidden', !widget.chat.allowEmoji);
  $('attachButton').classList.toggle('hidden', !widget.chat.allowAttachments);
  if (!widget.chat.allowAttachments) clearUploads();
  if (!widget.chat.allowEmoji) closeEmoji();
  for (const id of ['homeBranding', 'chatBranding']) {
    $(id).classList.toggle('hidden', !widget.brand.showBranding);
  }
  renderTeam();
  if (state.screen === 'home' || state.screen === 'messages') show(state.screen);
  if (state.screen === 'chat') renderChat();
  if (state.screen === 'home') renderHome();
}

/**
 * **What the visitor can expect, said honestly.** From the server's `availability`: closed, when the
 * team is back (in the visitor's own clock and language) and whether the assistant can help in the
 * meantime; open, how fast replies have really come over the last month. Without a measurement it
 * promises nothing it cannot keep.
 */
function availabilityLine() {
  const now = state.availability;
  if (!now) return t('replyUnknown');
  if (now.open === false && now.opensAt) {
    const back = backAt(new Date(now.opensAt));
    return now.assistant ? t('backAssistant', { back }) : back;
  }
  const minutes = now.replyMinutes;
  if (minutes === null || minutes === undefined) {
    return now.assistant ? t('assistantNow') : t('replyUnknown');
  }
  if (minutes < 1) return now.assistant ? t('assistantNow') : t('replyUnder1');
  if (minutes <= 5) return t('replyFew');
  if (minutes <= 20) return t('reply20');
  if (minutes <= 60) return t('replyHour');
  if (minutes <= 240) return t('replyHours');
  return t('replyDay');
}

/** "back today at 9:00", "tomorrow at 9:00", "Sunday at 9:00" — in the visitor's clock. */
function backAt(when) {
  const time = when.toLocaleTimeString(locale(), { hour: 'numeric', minute: '2-digit' });
  const today = new Date();
  const tomorrow = new Date(today.getTime() + 86_400_000);
  if (when.toDateString() === today.toDateString()) return t('backToday', { time });
  if (when.toDateString() === tomorrow.toDateString()) return t('backTomorrow', { time });
  return t('backOn', { day: when.toLocaleDateString(locale(), { weekday: 'long' }), time });
}

/** The line on Home under the team: a dot for in or out, and what to expect. */
function renderAvailability() {
  const holder = $('homeStatus');
  holder.replaceChildren();
  const now = state.availability;
  // A dot only when there are opening hours to be in or out of; without them it would be a guess.
  if (now && now.open !== null && now.open !== undefined) {
    const dot = el('i', `presence ${now.open ? 'in' : now.assistant ? 'away assistant' : 'away'}`);
    dot.setAttribute('aria-hidden', 'true');
    holder.append(dot);
  }
  holder.append(el('span', '', availabilityLine()));
}

/** A few faces and "Aisha, Layla and Hussain are here to help" — who a message will reach. */
function renderTeam() {
  const holder = $('homeTeam');
  holder.replaceChildren();
  const names = state.team.map((one) => one.name).filter(Boolean);
  const visible = state.widget.home.showTeam && names.length > 0;
  holder.classList.toggle('hidden', !visible);
  if (!visible) return;
  const who =
    names.length === 1
      ? t('oneHere', { name: names[0] })
      : t('manyHere', {
          names: names.slice(0, -1).join(t('listSeparator')),
          last: names[names.length - 1],
        });
  holder.append(faceStack(state.team, 'sm'), el('span', '', who));
}

/* ── Time ─────────────────────────────────────────────────────────────── */

const dayLabel = (value) => {
  const date = new Date(value);
  const today = new Date();
  if (date.toDateString() === today.toDateString()) return t('today');
  const yesterday = new Date(today.getTime() - 86_400_000);
  if (date.toDateString() === yesterday.toDateString()) return t('yesterday');
  return date.toLocaleDateString(locale(), { day: 'numeric', month: 'long' });
};

const clock = (value) =>
  new Date(value).toLocaleTimeString(locale(), { hour: 'numeric', minute: '2-digit' });

/** "Just now", "5m", "2h", "Yesterday", "3 Oct" — the list's time, which only has to place it. */
const ago = (value) => {
  const minutes = Math.floor((Date.now() - new Date(value).getTime()) / 60_000);
  if (minutes < 1) return t('justNow');
  if (minutes < 60) return t('minutesShort', { n: minutes });
  if (minutes < 24 * 60 && new Date(value).toDateString() === new Date().toDateString()) {
    return t('hoursShort', { n: Math.floor(minutes / 60) });
  }
  const label = dayLabel(value);
  return label === t('yesterday')
    ? label
    : new Date(value).toLocaleDateString(locale(), { day: 'numeric', month: 'short' });
};

/* ── Boot ─────────────────────────────────────────────────────────────── */

async function boot() {
  let inbox;
  try {
    inbox = await api(`/api/chat/public/${encodeURIComponent(publicKey)}`);
  } catch (error) {
    return unavailable(
      error.status === 404 ? t('linkInvalid') : t('chatUnavailable'),
      error.status === 404 ? t('linkInvalidBody') : t('tryAgainSoon'),
    );
  }

  state.business = inbox.business;
  state.greeting = inbox.greeting ?? '';
  state.requirePhone = Boolean(inbox.requirePhone);
  state.team = Array.isArray(inbox.team) ? inbox.team : [];
  state.availability = inbox.availability ?? null;
  document.title = `Chat · ${inbox.business}`;
  $('introBusiness').textContent = inbox.business;
  $('introGreeting').dir = 'auto';
  $('introGreeting').textContent = inbox.greeting;
  $('introPhoneField').classList.toggle('hidden', !inbox.requirePhone);
  /* Merged section by section over the defaults, exactly as the server does — a response from a
     deploy that predates a field must not leave this page reading `undefined`. */
  applyWidget(mergeWidget(inbox.widget));

  if (!inbox.isActive) {
    return unavailable(
      t('notTakingChats', { business: inbox.business }),
      t('notTakingChatsBody'),
    );
  }

  // Signed in first, when the site says who this is, so the history read next is theirs.
  const identity = await identityReady;
  if (identity) await identify(identity);

  if (state.token) {
    try {
      await refreshConversations();
    } catch (error) {
      if (error.status !== 401) return unavailable(t('chatUnavailable'), t('tryAgainSoon'));
      if (lostToken()) await refreshConversations().catch(() => forget());
    }
  }
  /**
   * **A signed-in session on a page that signed nobody in belongs to somebody else.** The site
   * logged them out (and maybe forgot `CnctChat.shutdown()`), or this is the next person at a
   * shared computer — either way their conversations are not this visitor's to read.
   */
  if (state.visitor?.identified && !state.signedIn) forget();
  land();
}

/**
 * Signs this browser in as the site's user. A failure — the client has not set it up, the
 * signature is wrong — is said in the console for the site's developer and otherwise ignored: the
 * visitor still gets a working, anonymous chat.
 */
async function identify(identity) {
  try {
    const data = await api(`/api/chat/public/${encodeURIComponent(publicKey)}/identify`, {
      method: 'POST',
      body: JSON.stringify(identity),
    });
    state.token = data.token;
    storage.set(STORE, data.token);
    state.visitor = data.visitor;
    state.signedIn = true;
    return true;
  } catch (error) {
    console.warn(`[cnct] Could not sign this visitor in: ${error.message}`);
    state.signedIn = false;
    return false;
  }
}

/** The site signed somebody in after the page loaded — an app with its own login. */
async function reidentify(identity) {
  state.socket?.close();
  state.socket = null;
  if (!(await identify(identity))) return;
  state.current = null;
  state.pending.clear();
  await refreshConversations().catch(() => {});
  land();
}

/**
 * `CnctChat.shutdown()`: the user logged out. Everything about them leaves this browser, and the
 * panel starts again as a stranger's.
 */
function shutdown() {
  state.socket?.close();
  state.socket = null;
  forget();
  state.current = null;
  state.pending.clear();
  state.ratingStage = {};
  state.justNamed = '';
  if (state.screen !== 'boot' && state.screen !== 'unavailable') land();
}

/**
 * The token we hold was refused. Before forgetting it, look again: another tab may have just signed
 * this browser in and written a new one, and forgetting would delete *that*.
 */
function lostToken() {
  const stored = storage.get(STORE);
  if (stored && stored !== state.token) {
    state.token = stored;
    return true;
  }
  forget();
  return false;
}

/** Where a visitor arrives: Home if there is one, otherwise straight into a conversation. */
function land() {
  const live = liveConversation();
  // The live conversation's socket stays open wherever they are, so news reaches the tab bar.
  if (live) connectSocket();
  if (state.widget.home.enabled) return goTab('home');
  if (live) return void openConversation(live.id);
  startNew();
}

function unavailable(title, body) {
  $('unavailableTitle').textContent = title;
  $('unavailableBody').textContent = body;
  show('unavailable');
}

function forget() {
  storage.remove(STORE);
  storage.remove(SEEN);
  clearUploads();
  state.reported = {};
  state.token = null;
  state.signedIn = false;
  state.visitor = null;
  state.conversations = [];
  state.seen = {};
}

const liveConversation = () =>
  state.conversations.find((conversation) => conversation.status !== 'CLOSED') ?? null;

async function refreshConversations() {
  if (!state.token) return;
  const data = await api('/api/chat/public/conversations');
  state.visitor = data.visitor ?? state.visitor;
  state.conversations = data.conversations ?? [];
  if (state.screen === 'home') renderHome();
  if (state.screen === 'messages') renderMessages();
}

/* ── Home and the list ────────────────────────────────────────────────── */

function goTab(tab) {
  state.tab = tab;
  show(tab);
  if (tab === 'home') renderHome();
  if (tab === 'messages') renderMessages();
}

const isUnread = (conversation) => {
  const preview = conversation.preview;
  if (!preview || preview.speaker === 'CALLER') return false;
  const seen = state.seen[conversation.id];
  return !seen || new Date(preview.createdAt) > new Date(seen);
};

function markSeen(conversation) {
  if (!conversation?.id || !panelOpen) return;
  const messages = state.current?.conversation?.id === conversation.id ? state.current.messages : [];
  const last = messages[messages.length - 1]?.createdAt ?? conversation.preview?.createdAt;
  if (!last) return;
  state.seen[conversation.id] = new Date(last).toISOString();
  storage.set(SEEN, JSON.stringify(state.seen));
  const row = state.conversations.find((one) => one.id === conversation.id);
  if (row && messages.length) row.preview = { ...(row.preview ?? {}), ...messages[messages.length - 1] };
  if (state.screen === 'chat' && document.visibilityState === 'visible') reportRead(conversation, messages);
}

/**
 * **Tells the team their reply was read** — the ticks in the console. Only for a reply that is on
 * screen in an open panel on a visible page, and once per reply: the newest one reported is kept,
 * and the next report waits for a newer one. A failed report is forgotten so the next look retries.
 */
function reportRead(conversation, messages) {
  if (preview || !state.token || !conversation?.id) return;
  const reply = [...messages]
    .reverse()
    .find((message) => (message.speaker === 'OPERATOR' || message.speaker === 'AI') && message.id);
  if (!reply || state.reported[conversation.id] === reply.id) return;
  state.reported[conversation.id] = reply.id;
  api(`/api/chat/public/conversations/${encodeURIComponent(conversation.id)}/read`, {
    method: 'POST',
  }).catch(() => {
    if (state.reported[conversation.id] === reply.id) delete state.reported[conversation.id];
  });
}

/** The one line a list shows for a conversation: what was said last, and by whom. */
function previewText(conversation) {
  const preview = conversation.preview;
  if (!preview) return t('startNew');
  const body = wordsOf(preview).replace(/\s+/g, ' ').trim();
  const text = body || (preview.attachments?.length ? t('sentAFile') : '');
  return preview.speaker === 'CALLER' ? `${t('you')}: ${text}` : text;
}

function previewSender(conversation) {
  const preview = conversation.preview;
  if (!preview || preview.speaker === 'CALLER') return conversation.assignee ?? null;
  return preview.sender ?? null;
}

/** A row for a list — Home's "recent" tile and every entry under Messages share it. */
function conversationRow(conversation) {
  const sender = previewSender(conversation);
  const unread = isUnread(conversation);
  const row = el('span', `convo${unread ? ' unread' : ''}${conversation.status === 'CLOSED' ? ' closed' : ''}`);
  row.append(face(sender, 'md'));
  const main = el('span', 'convo-main');
  const text = el('span', 'convo-text', previewText(conversation));
  text.dir = 'auto';
  const who = sender?.name || state.business;
  const meta = el('span', 'convo-meta');
  meta.append(el('span', '', who));
  if (sender?.kind === 'ai') meta.append(el('span', 'chip', 'AI'));
  meta.append(el('span', '', `· ${ago(conversation.preview?.createdAt ?? conversation.lastMessageAt ?? conversation.startedAt)}`));
  main.append(text, meta);
  row.append(main);
  if (unread) row.append(el('i', 'dot'));
  else if (conversation.status !== 'CLOSED') row.append(el('span', 'live', t('open')));
  else if (conversation.rating && RATINGS.includes(conversation.rating.score)) {
    const rated = icon(`${FACES}#rating-${conversation.rating.score}`, 'rated');
    rated.setAttribute('aria-hidden', 'false');
    rated.setAttribute('role', 'img');
    rated.setAttribute('aria-label', t(`rating${conversation.rating.score}`));
    row.append(rated);
  } else row.append(icon('#c-chevron', 'chev'));
  return row;
}

/** A row that opens its conversation — Home's short list and the full one share it. */
function rowButton(conversation) {
  const button = el('button', 'list-row');
  button.type = 'button';
  button.setAttribute('role', 'listitem');
  button.append(conversationRow(conversation));
  button.addEventListener('click', () => void openConversation(conversation.id));
  return button;
}

/** Three on Home: enough to pick the last one up, few enough that Home stays a front door. */
const RECENT = 3;

function renderHome() {
  renderAvailability();
  const live = liveConversation();
  $('homeStartLabel').textContent = live ? t('carryOn') : t('writeToUs');
  // The live one first — it is the one they came back for — then the rest, newest first.
  const ordered = live
    ? [live, ...state.conversations.filter((one) => one !== live)]
    : state.conversations;
  const shown = ordered.slice(0, RECENT);
  $('homeRecent').classList.toggle('hidden', shown.length === 0);
  $('homeRecentList').replaceChildren(...shown.map(rowButton));
  $('homeAll').classList.toggle('hidden', state.conversations.length <= RECENT);
  $('homeAll').textContent = t('seeAll', { count: state.conversations.length });
}

function renderMessages() {
  const list = $('convoList');
  list.replaceChildren(...state.conversations.map(rowButton));
  $('convoEmpty').classList.toggle('hidden', state.conversations.length > 0);
  list.classList.toggle('hidden', state.conversations.length === 0);
  $('forgetMe').classList.toggle('hidden', !state.token);
  $('messagesStart').querySelector('span').textContent = liveConversation()
    ? t('carryOnOpen')
    : t('startNew');
}

/* ── Opening and starting ─────────────────────────────────────────────── */

async function openConversation(id) {
  closeConfirm();
  clearError($('chatError'));
  const ticket = ++state.navigation;
  const known = state.conversations.find((one) => one.id === id) ?? null;
  state.current = {
    conversation: known,
    messages: state.current?.conversation?.id === id ? state.current.messages : [],
  };
  show('chat');
  renderChat();
  try {
    const data = await api(`/api/chat/public/conversations/${encodeURIComponent(id)}`);
    state.visitor = data.visitor ?? state.visitor;
    // They may have moved on while it loaded; only draw it if this is still the one on screen.
    if (ticket !== state.navigation) return;
    state.current = { conversation: data.conversation, messages: data.messages ?? [] };
    for (const message of state.current.messages) {
      if (message.clientKey) state.pending.delete(message.clientKey);
    }
    syncRow(data.conversation, data.messages);
  } catch (error) {
    if (ticket !== state.navigation) return;
    if (error.status === 401) {
      if (lostToken()) await refreshConversations().catch(() => forget());
      return land();
    }
    showError($('chatError'), error.message);
  }
  renderChat();
  markSeen(state.current?.conversation);
  if (state.current?.conversation?.status !== 'CLOSED') connectSocket();
}

/**
 * "Send us a message", from anywhere. With a live conversation that *is* the message — a visitor
 * has one at a time — and without one it is either the short form or the composer, as the client
 * chose.
 */
function startNew(opener = '') {
  closeConfirm();
  const live = liveConversation();
  if (live) {
    return void openConversation(live.id).then(() => {
      if (!opener) return;
      $('input').value = opener;
      void send();
    });
  }
  state.opener = opener;
  const named = state.visitor && !state.visitor.anonymous;
  if (state.widget.chat.start === 'details' && !named) {
    show('intro');
    $('introBack').classList.toggle('hidden', !state.widget.home.enabled && !state.conversations.length);
    $('introName').focus();
    return;
  }
  state.navigation += 1;
  state.current = { conversation: null, messages: [] };
  show('chat');
  renderChat();
  $('input').focus({ preventScroll: true });
  if (opener) {
    $('input').value = opener;
    state.opener = '';
    void send();
  }
}

/** Puts text in the composer, caret at the end, without sending it. */
function prefill(text) {
  if (!text) return;
  const input = $('input');
  input.value = text;
  autoGrow();
  input.focus({ preventScroll: true });
  input.setSelectionRange(text.length, text.length);
}

/**
 * `CnctChat.showNewMessage(text)`: the conversation they would get from "Write to us", with the
 * host's words already typed — never sent, because the host is suggesting, not speaking for them.
 */
function compose(text) {
  if (state.screen === 'boot' || state.screen === 'unavailable') return;
  closeConfirm();
  const live = liveConversation();
  if (live) return void openConversation(live.id).then(() => prefill(text));
  const named = state.visitor && !state.visitor.anonymous;
  if (state.widget.chat.start === 'details' && !named) {
    state.prefill = text;
    show('intro');
    $('introBack').classList.toggle('hidden', !state.widget.home.enabled && !state.conversations.length);
    $('introName').focus();
    return;
  }
  state.navigation += 1;
  state.current = { conversation: null, messages: [] };
  show('chat');
  renderChat();
  prefill(text);
}

/** `CnctChat.showConversations()`, and the reply preview's "take me to it". */
function navigate(to) {
  if (state.screen === 'boot' || state.screen === 'unavailable') return;
  if (to === 'messages') return goTab('messages');
  if (to === 'home' && state.widget.home.enabled) return goTab('home');
  if (to === 'live') {
    const live = liveConversation();
    if (live) return void openConversation(live.id);
  }
}

/**
 * Starts the conversation on the server — from the form, or from the first message of a visitor
 * who was let type first. A returning visitor sends no name and keeps the one they gave last time.
 */
async function startSession(details = {}) {
  const session = await api(`/api/chat/public/${encodeURIComponent(publicKey)}/session`, {
    method: 'POST',
    body: JSON.stringify({
      ...details,
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    }),
  });
  if (session.token) {
    state.token = session.token;
    storage.set(STORE, session.token);
  }
  state.visitor = session.visitor ?? state.visitor;
  state.current = { conversation: session.conversation, messages: session.messages ?? [] };
  syncRow(session.conversation, session.messages ?? []);
  connectSocket();
  return session;
}

/**
 * The conversation exists on the server — started now if this is the first thing they do. A
 * message and a file dropped in the same moment share one start rather than racing to make two.
 */
function ensureSession() {
  if (state.current?.conversation) return Promise.resolve();
  state.starting ??= startSession({})
    .then(() => renderChat())
    .finally(() => {
      state.starting = null;
    });
  return state.starting;
}

/** Keeps the list's row for a conversation in step with what was just learned about it. */
function syncRow(conversation, messages) {
  if (!conversation?.id) return;
  const last = messages?.length ? messages[messages.length - 1] : null;
  const index = state.conversations.findIndex((one) => one.id === conversation.id);
  const row = {
    ...(index >= 0 ? state.conversations[index] : {}),
    ...conversation,
    ...(last ? { preview: last, lastMessageAt: last.createdAt } : {}),
  };
  if (index >= 0) state.conversations[index] = row;
  else state.conversations.unshift(row);
  state.conversations.sort(
    (a, b) => new Date(b.lastMessageAt ?? b.startedAt) - new Date(a.lastMessageAt ?? a.startedAt),
  );
}

async function submitIntro(event) {
  event.preventDefault();
  clearError($('introError'));
  /* In the builder's preview there is no session to start — walking to the next screen is the
     whole of what "start" means there. */
  if (preview) {
    state.current = { conversation: PREVIEW.live, messages: PREVIEW.liveMessages };
    show('chat');
    renderChat();
    return;
  }
  const displayName = $('introName').value.trim();
  if (!displayName) return;
  const phone = $('introPhone').value.trim();
  const button = $('introStart');
  button.disabled = true;
  button.textContent = t('starting');
  try {
    await startSession({ displayName, ...(phone ? { phone } : {}) });
    show('chat');
    renderChat();
    $('input').focus({ preventScroll: true });
    if (state.opener) {
      $('input').value = state.opener;
      state.opener = '';
      void send();
    } else if (state.prefill) {
      prefill(state.prefill);
      state.prefill = '';
    }
  } catch (error) {
    showError(
      $('introError'),
      error.status === 429
        ? t('tooManyChats')
        : error.message,
    );
  } finally {
    button.disabled = false;
    button.textContent = t('startChatting');
  }
}

/* ── The conversation screen ──────────────────────────────────────────── */

const isLive = (conversation) => !conversation || conversation.status !== 'CLOSED';

/** The assistant that spoke last, if the conversation is still the assistant's. */
const lastAssistant = (messages) => {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    if (messages[index].sender?.kind === 'ai') return messages[index].sender;
    if (messages[index].speaker === 'OPERATOR') return null;
  }
  return null;
};

/** Everything on the conversation screen: the header, the thread and the cards under it. */
function renderChat() {
  renderHeader();
  renderThread();
  renderCards();
}

/** "Today" and "yesterday" read as words; anything older reads as the date. */
const endedWhen = (value) => {
  const label = dayLabel(value);
  if (label === t('today')) return t('endedToday');
  if (label === t('yesterday')) return t('endedYesterday');
  return t('endedOn', { date: label });
};

/** The header, the composer and the footer — what changes when the conversation's state does. */
function renderHeader() {
  const conversation = state.current?.conversation ?? null;
  const messages = state.current?.messages ?? [];
  const live = isLive(conversation);
  const assistant = conversation?.withPerson ? null : lastAssistant(messages);

  // The header: who they are talking to, as specifically as we honestly can.
  const faces = $('chatFaces');
  faces.replaceChildren();
  let title = state.business;
  let line = availabilityLine();
  if (conversation?.assignee) {
    faces.append(face(conversation.assignee, 'lg'));
    title = conversation.assignee.name || state.business;
    line = conversation.assignee.name ? state.business : t('withPerson');
  } else if (assistant) {
    faces.append(face(assistant, 'lg'));
    title = assistant.name || state.business;
    line = `${t('aiAssistant')} · ${t('teamCanHelp')}`;
  } else {
    faces.append(
      state.widget.home.showTeam && state.team.length ? faceStack(state.team, 'md') : face(null, 'lg'),
    );
    if (conversation?.withPerson) line = t('withPerson');
  }
  if (!live) line = t('ended', { when: endedWhen(conversation.closedAt ?? conversation.startedAt) });
  $('chatTitle').textContent = title;
  $('chatState').textContent = line;

  $('chatBack').classList.toggle('hidden', !state.widget.home.enabled && !state.conversations.length && !conversation);
  $('chatEnd').classList.toggle(
    'hidden',
    !state.widget.chat.allowEndConversation || !conversation || !live,
  );
  $('composer').classList.toggle('hidden', !live || isConfirming());
  $('closedFoot').classList.toggle('hidden', live);
}

/**
 * The messages, in groups — one group per run of the same speaker, signed once underneath. It is
 * what lets a thread with an assistant, a hand-over and a person in it read as three voices rather
 * than one stream.
 */
function renderThread() {
  const thread = $('threadMessages') ?? setUpThread();
  const scroller = $('thread');
  const pinned = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 90;
  thread.replaceChildren();

  const conversation = state.current?.conversation ?? null;
  // A different conversation, or the first paint of this one with anything in it: nothing is "new".
  if (state.drawn.for !== (conversation?.id ?? 'draft')) {
    state.drawn = { for: conversation?.id ?? 'draft', keys: new Set(), cards: new Set() };
  }
  const first = state.drawn.keys.size === 0;
  const live = isLive(conversation);
  const all = (state.current?.messages ?? []).map((message) => ({
    ...message,
    side: message.speaker === 'CALLER' ? 'you' : 'them',
  }));
  // A new conversation with nothing sent yet still opens with the business saying hello — the same
  // greeting the server will write as its first line the moment the session starts.
  if (!conversation && state.greeting) {
    all.push({ id: 'greeting', speaker: 'SYSTEM', body: state.greeting, createdAt: new Date().toISOString(), side: 'them' });
  }
  if (live) {
    for (const item of state.pending.values()) all.push({ ...item, side: 'you' });
  }

  let lastDay = '';
  let group = null;
  const flush = () => {
    if (!group) return;
    const node = drawGroup(group);
    const keys = group.messages.map((message) => message.clientKey || message.id);
    const fresh = keys.map((key) => !first && !state.drawn.keys.has(key));
    // A whole new group rises in; one more bubble on a group already there rises on its own.
    if (fresh.every(Boolean)) node.classList.add('fresh');
    else {
      const bubbles = node.querySelectorAll('.msg');
      fresh.forEach((isNew, index) => isNew && bubbles[index]?.classList.add('fresh'));
    }
    for (const key of keys) state.drawn.keys.add(key);
    thread.append(node);
    group = null;
  };
  for (const message of all) {
    const day = dayLabel(message.createdAt);
    if (day !== lastDay) {
      flush();
      thread.append(el('div', 'stamp', day));
      lastDay = day;
    }
    const key = signer(message);
    if (!group || group.key !== key) {
      flush();
      group = { key, side: message.side, messages: [] };
    }
    group.messages.push(message);
  }
  flush();

  /* "Seen" under the visitor's last words once somebody on the team has read them — only when
     theirs are the last words: after a reply, the reply says it better. */
  const lastShown = all[all.length - 1];
  if (lastShown?.side === 'you' && lastShown.id && !lastShown.pending && !lastShown.failed) {
    const seenAt = conversation?.seenAt ? new Date(conversation.seenAt) : null;
    const byline = thread.lastElementChild?.querySelector('.byline');
    if (seenAt && seenAt >= new Date(lastShown.createdAt) && byline) {
      const seen = el('span', 'seen');
      seen.append(icon('#c-check-check'), el('span', '', t('seen')));
      byline.append(el('span', '', '·'), seen);
    }
  }

  /* What a flow is waiting for: its choices under the thread, and the composer asking for the
     right kind of answer. Both go the moment anything else is said. */
  const awaiting = flowAwaiting(all, live);
  if (awaiting?.choices.length) {
    const key = `choices:${awaiting.messageId}`;
    const node = choicesNode(awaiting);
    if (!first && !state.drawn.keys.has(key)) node.classList.add('fresh');
    state.drawn.keys.add(key);
    thread.append(node);
  }
  composerAsks(awaiting);

  /* Replies the assistant offered with its last answer, as chips: tap one and it is sent. Only while
     nobody has spoken since — anything the visitor or a person says moves the conversation on. Read
     back over the assistant's trailing messages, because pictures it sent after the words (an eSIM
     code, a phone) arrive as messages of their own and carry none. Never beside a flow's choices:
     one row of answers to tap at a time. */
  let offered = [];
  if (live && !state.draft && !awaiting?.choices.length) {
    for (let index = all.length - 1; index >= 0 && all[index].speaker === 'AI'; index -= 1) {
      if (all[index].suggestions?.length) {
        offered = all[index].suggestions;
        break;
      }
    }
  }
  if (offered.length) {
    const chips = el('div', 'suggestions');
    for (const suggestion of offered.slice(0, 3)) {
      if (typeof suggestion !== 'string' || !suggestion.trim()) continue;
      const button = el('button', 'opener');
      button.type = 'button';
      const label = el('span', '', suggestion);
      label.dir = 'auto';
      button.append(label);
      button.addEventListener('click', () => {
        $('input').value = suggestion;
        void send();
      });
      chips.append(button);
    }
    thread.append(chips);
  }

  if (state.draft && live) {
    const writing = el('div', 'group them');
    if (state.draft.body.trim()) {
      writing.append(bubble({ body: state.draft.body, attachments: [] }));
      writing.lastElementChild.classList.add('head', 'draft');
    }
    if (state.draft.status) {
      const status = el('div', 'draft-status', state.draft.status);
      status.dir = 'auto';
      writing.append(status);
    }
    if (!state.draft.body.trim() && !state.draft.status) {
      const dots = el('div', 'typing');
      dots.append(...[0, 1, 2].map(() => document.createElement('i')));
      writing.append(dots);
    }
    thread.append(writing);
  } else if (state.typingTimer && live) {
    const typing = el('div', 'group them');
    const dots = el('div', 'typing');
    dots.append(...[0, 1, 2].map(() => document.createElement('i')));
    typing.append(dots);
    thread.append(typing);
  }
  if (pinned) scroller.scrollTop = scroller.scrollHeight;
}

/**
 * One run of the same speaker: the bubbles, then a byline under them — the face, the name, an
 * "AI" tag where it applies, and the time. Signed underneath rather than beside, like a letter:
 * the bubbles get the full width, and the name is read after what was said rather than before it.
 */
function drawGroup(group) {
  const node = el('div', `group ${group.side}`);
  const first = group.messages[0];
  const last = group.messages[group.messages.length - 1];
  group.messages.forEach((message, index) => {
    const bubbleNode = bubble(message);
    // The first bubble of a run is fully round; the rest tuck in towards their speaker.
    if (index === 0) bubbleNode.classList.add('head');
    node.append(bubbleNode);
  });
  if (last.failed) return node;
  const byline = el('div', 'byline');
  if (group.side === 'them') {
    byline.append(face(first.sender ?? null, 'xs'));
    const name = el('span', 'who-name', signerName(first));
    name.dir = 'auto';
    byline.append(name);
    if (first.speaker === 'AI') byline.append(el('span', 'chip', 'AI'));
    byline.append(el('span', '', '·'));
  }
  byline.append(el('span', '', last.pending ? t('sending') : clock(last.createdAt)));
  node.append(byline);
  return node;
}

function bubble(message) {
  const flow = drawsAsFlow(message) ? message.flow : null;
  const body = flow ? String(flow.text ?? '') : String(message.body ?? '');
  const onlyFiles = !body.trim() && Boolean(message.attachments?.length);
  const node = el(
    'div',
    ['msg', message.pending ? 'pending' : '', message.failed ? 'failed' : '', onlyFiles ? 'only-files' : '']
      .filter(Boolean)
      .join(' '),
  );
  if (message.id) node.dataset.messageId = message.id;
  if (message.replyTo) node.append(quoteNode(message.replyTo));
  if (flow?.media) {
    node.classList.add('has-media');
    node.append(flowMediaNode(flow.media));
  }
  if (!onlyFiles) {
    for (const line of body.split('\n')) {
      const row = el('div');
      row.append(...(line === '' ? ['\u00a0'] : webLinked(line)));
      row.dir = 'auto';
      node.append(row);
    }
  }
  if (flow?.footer) {
    const footer = el('div', 'msg-footer', flow.footer);
    footer.dir = 'auto';
    node.append(footer);
  }
  for (const attachment of message.attachments ?? []) node.append(attachmentNode(attachment));
  if (message.failed) {
    const retry = el('button', 'retry', t('notSent'));
    retry.type = 'button';
    retry.addEventListener('click', () => void resend(message.clientKey));
    node.append(retry);
  }
  return node;
}

/**
 * **The message this one replies to, drawn above its words** as a phone draws a swipe-reply: a bar,
 * whose it was, and its first words, or the kind of file it was. Pressing it scrolls to that message
 * if it is in the thread on screen, looked up then rather than now: it may be drawn after this one. The words are the server's snapshot of our own row (see
 * `publicQuote`), so nothing here is anything the quoting side made up.
 */
function quoteNode(quote) {
  const node = el(quote.messageId ? 'button' : 'div', 'quote-ref');
  if (quote.messageId) {
    node.type = 'button';
    node.setAttribute('aria-label', t('showQuoted'));
    node.addEventListener('click', () => {
      const shown = document.querySelector(`[data-message-id="${CSS.escape(quote.messageId)}"]`);
      shown?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      shown?.classList.add('flash');
      setTimeout(() => shown?.classList.remove('flash'), 1200);
    });
  }
  if (quote.mine) node.append(el('span', 'quote-who', t('quoteYou')));
  const words = quote.excerpt
    ? quote.excerpt
    : quote.attachmentKind
      ? t(`quote${quote.attachmentKind}`)
      : t('quoteEarlier');
  const line = el('span', 'quote-words', words);
  line.dir = 'auto';
  node.append(line);
  return node;
}

/**
 * **A flow's message, drawn from its parts rather than its `body`.**
 *
 * On the web a flow message's `body` is numbered text — "1. Book a visit … Reply with the number" —
 * because that reads correctly anywhere that knows nothing of flows. This page does know, so it draws
 * the words, the picture and the footer itself and puts the choices under the thread as buttons
 * (`flowAwaiting`). A message stored before the server sent the words (`text` null) keeps its body.
 */
function drawsAsFlow(message) {
  return Boolean(message?.flow && typeof message.flow.text === 'string');
}

/** What a message says in words, for the lists and the launcher's card. */
function wordsOf(message) {
  return drawsAsFlow(message) ? String(message.flow.text) : String(message?.body ?? '');
}

/**
 * **What a flow is waiting for, if anything** — the choices to draw, and how, or the kind of answer
 * the composer should ask for.
 *
 * Only ever the last thing said, and only on a conversation still open: once the visitor answers,
 * or anybody says anything, the choices go, as quick replies do everywhere. The flow still reads a
 * typed answer — the title, or its number — so nobody is stuck if they would rather type.
 *
 * Choices with descriptions are rows, because a description needs the width; short ones are pills.
 * Pure, so `tests/chat-flow-choices.test.ts` runs it as it is.
 */
function flowAwaiting(messages, live) {
  if (!live) return null;
  const last = messages[messages.length - 1];
  if (!last || last.speaker !== 'AI' || last.pending || !drawsAsFlow(last)) return null;
  const flow = last.flow;
  const choices = Array.isArray(flow.choices)
    ? flow.choices.filter((choice) => choice && typeof choice.id === 'string' && typeof choice.title === 'string')
    : [];
  if (choices.length) {
    return {
      messageId: last.id,
      choices,
      style: choices.some((choice) => choice.description) ? 'rows' : 'pills',
      label: typeof flow.listButton === 'string' ? flow.listButton : null,
      expects: null,
    };
  }
  if (flow.expects) return { messageId: last.id, choices: [], style: null, label: null, expects: flow.expects };
  return null;
}

/** The kind of keyboard and the words in the box, for each answer a question can ask for. */
const ANSWER_KEYBOARD = {
  name: { mode: 'text', placeholder: 'answerName' },
  number: { mode: 'decimal', placeholder: 'answerNumber' },
  email: { mode: 'email', placeholder: 'answerEmail' },
  phone: { mode: 'tel', placeholder: 'answerPhone' },
  date: { mode: 'text', placeholder: 'answerDate' },
};

/**
 * The composer, asking for what the flow wants: a phone shows the number pad for a number and the
 * email keyboard for an address, and the box says what to type. Back to "Write a message…" the
 * moment the flow is not waiting on anything.
 */
function composerAsks(awaiting) {
  const input = $('input');
  if (!input) return;
  const keyboard = awaiting?.expects ? ANSWER_KEYBOARD[awaiting.expects] : null;
  const placeholder = keyboard
    ? t(keyboard.placeholder)
    : awaiting?.choices.length
      ? t('chooseOrWrite')
      : t('writeMessage');
  if (input.placeholder !== placeholder) input.placeholder = placeholder;
  const mode = keyboard?.mode ?? 'text';
  if (input.inputMode !== mode) input.inputMode = mode;
}

function choicesNode(awaiting) {
  const wrap = el('div', `choices ${awaiting.style}`);
  wrap.setAttribute('role', 'group');
  wrap.setAttribute('aria-label', awaiting.label || t('choices'));
  if (awaiting.style === 'rows' && awaiting.label) wrap.append(el('div', 'choices-label', awaiting.label));
  awaiting.choices.forEach((choice, index) => {
    const button = el('button', 'choice');
    button.type = 'button';
    button.style.setProperty('--i', String(index));
    const title = el('span', 'choice-title', choice.title);
    title.dir = 'auto';
    if (awaiting.style === 'rows') {
      const words = el('span', 'choice-words');
      words.append(title);
      if (choice.description) {
        const description = el('span', 'choice-description', choice.description);
        description.dir = 'auto';
        words.append(description);
      }
      button.append(words, icon('#c-chevron', 'chev'));
    } else {
      button.append(title);
    }
    button.addEventListener('click', () => void choose(choice));
    wrap.append(button);
  });
  return wrap;
}

/** A picture, film or document a flow put above its words. Only ever an http(s) address. */
function flowMediaNode(media) {
  const wrap = el('div', 'flow-media');
  if (!/^https?:\/\//i.test(String(media.url ?? ''))) return wrap;
  if (media.kind === 'image' || media.kind === 'video') wrap.classList.add('framed');
  if (media.kind === 'image') {
    const image = el('img');
    image.src = media.url;
    image.alt = '';
    image.loading = 'lazy';
    image.referrerPolicy = 'no-referrer';
    wrap.append(image);
  } else if (media.kind === 'video') {
    const video = el('video');
    video.src = media.url;
    video.controls = true;
    video.preload = 'metadata';
    video.playsInline = true;
    wrap.append(video);
  } else {
    const name = media.filename || t('attachment');
    const link = el('a', 'file', name);
    link.href = media.url;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.setAttribute('aria-label', t('openDocument', { name }));
    wrap.append(link);
  }
  return wrap;
}

/**
 * A tap on a flow's choice: sent as the visitor's words — the title they tapped — with the choice's
 * id beside it, so the flow follows the button exactly rather than reading the title as typing. The
 * server keeps the id only if this conversation was offered it.
 */
async function choose(choice) {
  const conversation = state.current?.conversation ?? null;
  if (!isLive(conversation) || preview) return;
  closeEmoji();
  clearError($('chatError'));
  const clientKey = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  state.pending.set(clientKey, {
    clientKey,
    body: choice.title,
    replyId: choice.id,
    attachments: [],
    createdAt: new Date().toISOString(),
    pending: true,
  });
  renderThread();
  await deliver(clientKey);
}

/**
 * **A link in a message is a link.** A payment link an operator sent sat in the bubble as text the
 * visitor had to select and paste — on the one page whose whole job is that conversation.
 *
 * The address shown is the address opened, character for character, and only `http`/`https` are
 * ever produced, so a stranger's message cannot dress one place up as another. Starts at `http://`,
 * `https://` or `www.` in any case (a phone capitalises the first word, which a pasted link often
 * is); ends before whitespace and before the punctuation a sentence puts after it — a full stop,
 * the Arabic comma, a closing bracket the address did not open. The same pattern as the console's
 * `web/components/thread/web-link.tsx`; `tests/chat-web-links.test.ts` holds the two together.
 *
 * Still no HTML: the link is an element and every piece of text lands as a text node.
 */
const WEB_ADDRESS = /(?:[Hh][Tt][Tt][Pp][Ss]?:\/\/|\b[Ww][Ww][Ww]\.)(?:[^\s()<>"]|\([^\s()<>"]*\))*(?:[^\s()<>".,:;!?'’”»\]}*_~،؛؟…]|\([^\s()<>"]*\))/g;

/** A line as text and addresses, in order — `{ text }` or `{ text, href }`. */
function webAddressesIn(line) {
  const parts = [];
  let cursor = 0;
  for (const match of line.matchAll(WEB_ADDRESS)) {
    if (match.index > cursor) parts.push({ text: line.slice(cursor, match.index) });
    const text = match[0];
    parts.push({ text, href: /^https?:\/\//i.test(text) ? text : `https://${text}` });
    cursor = match.index + text.length;
  }
  if (cursor < line.length) parts.push({ text: line.slice(cursor) });
  return parts;
}

function webLinked(line) {
  return webAddressesIn(line).map(({ text, href }) => {
    if (!href) return text;
    const link = el('a', 'web-link', text);
    link.href = href;
    // Out of the chat — which may be a frame inside somebody's website — never over it.
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    // Left-to-right inside an Arabic sentence, and out of the line's own `dir="auto"`.
    link.dir = 'ltr';
    return link;
  });
}

function attachmentNode(attachment) {
  const wrap = el('div', 'attachment');
  const label = attachment.filename || t('attachment');
  const image = attachment.kind === 'IMAGE' || (attachment.mimeType || '').startsWith('image/');

  const holder = document.createElement(image ? 'img' : 'a');
  if (image) {
    holder.alt = label;
  } else {
    holder.textContent = label;
    holder.className = 'file';
    holder.download = label;
  }
  wrap.append(holder);

  // Already here — fetched before, or a file this visitor just sent from their own device.
  const ready = attachment.localUrl ?? fileUrls.get(attachment.id);
  if (ready) {
    holder[image ? 'src' : 'href'] = ready;
    return wrap;
  }
  void fileUrl(attachment)
    .then((url) => {
      holder[image ? 'src' : 'href'] = url;
    })
    .catch(() => wrap.replaceChildren(el('span', 'file', t('couldNotLoad', { name: label }))));
  return wrap;
}

/**
 * Each file's blob URL, fetched once per page. The thread is redrawn whole on every message, and
 * without this every photo in it was downloaded again each time anybody said anything.
 */
const fileUrls = new Map();
const fileFetches = new Map();

/* Fetched with the token and handed over as a blob URL. A plain `src` would need the secret in the
   query string, where it lands in every proxy's access log. */
function fileUrl(attachment) {
  let fetching = fileFetches.get(attachment.id);
  if (!fetching) {
    fetching = fetch(`/api/chat/public/attachments/${encodeURIComponent(attachment.id)}`, {
      headers: state.token ? { 'x-chat-token': state.token } : {},
    })
      .then((response) => {
        if (!response.ok) throw new Error(String(response.status));
        return response.blob();
      })
      .then((blob) => {
        const url = URL.createObjectURL(blob);
        fileUrls.set(attachment.id, url);
        return url;
      })
      // A failure is not remembered: the next redraw asks again.
      .finally(() => fileFetches.delete(attachment.id));
    fileFetches.set(attachment.id, fetching);
  }
  return fetching;
}

/**
 * The thread is two layers: the messages, redrawn whenever one arrives, and the cards under them —
 * the name card and the rating — redrawn only when *they* change. A card is a form, and a form
 * rebuilt under somebody's fingers loses what they were typing and where the caret was.
 */
function setUpThread() {
  const scroller = $('thread');
  const messages = el('div', 'thread-messages');
  messages.id = 'threadMessages';
  const cards = el('div', 'thread-cards');
  cards.id = 'threadCards';
  scroller.append(messages, cards);
  return messages;
}

function renderCards() {
  if (!$('threadCards')) setUpThread();
  const cards = $('threadCards');
  const scroller = $('thread');
  // Read before redrawing: a card that appears under somebody reading the bottom of the thread is
  // scrolled into view, and one that appears while they are reading further up is left for them.
  const pinned = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 90;
  requestAnimationFrame(() => {
    if (pinned) scroller.scrollTo({ top: scroller.scrollHeight, behavior: 'smooth' });
  });
  /**
   * The cards are forms, and they are rebuilt whenever the thread is re-read — a reconnect, a tab
   * coming back into view. The values survive in `state`; this carries the caret across too, so
   * somebody halfway through typing their name keeps typing rather than clicking back in.
   */
  const active = document.activeElement;
  const field = cards.contains(active) ? active.dataset.field : undefined;
  const caret = field ? [active.selectionStart, active.selectionEnd] : null;
  cards.replaceChildren();
  const conversation = state.current?.conversation ?? null;
  const messages = state.current?.messages ?? [];
  const spoke = messages.some((message) => message.speaker === 'CALLER');

  if (state.justNamed) {
    const thanks = el('div', 'note-card');
    thanks.append(icon('#c-check', 'tick'), el('span', '', t('detailsThanks', { name: state.justNamed })));
    cards.append(thanks);
  } else if (conversation && spoke && state.visitor?.anonymous) {
    cards.append(detailsCard());
  }

  if (conversation && !isLive(conversation)) {
    cards.append(el('div', 'ended', t('endedDivider')));
    const askable = state.widget.chat.askForRating && (conversation.ratable || conversation.rating);
    if (askable) cards.append(ratingCard(conversation));
  }

  const again = field ? cards.querySelector(`[data-field="${field}"]`) : null;
  if (again) {
    again.focus({ preventScroll: true });
    if (caret && again.setSelectionRange) again.setSelectionRange(caret[0], caret[1]);
  }
}

/** " fresh" the first time a card is drawn for this conversation, so it animates in once. */
function freshCard(key) {
  if (state.drawn.cards.has(key)) return '';
  state.drawn.cards.add(key);
  return ' fresh';
}

function detailsCard() {
  const card = el('form', `card-in-thread details${freshCard('details')}`);
  card.append(el('strong', '', t('detailsTitle')), el('p', 'muted', t('detailsBody')));
  const name = el('input');
  name.placeholder = t('yourName');
  name.maxLength = 60;
  name.autocomplete = 'given-name';
  name.required = true;
  name.value = state.detailsDraft.name;
  name.dataset.field = 'details-name';
  name.addEventListener('input', () => (state.detailsDraft.name = name.value));
  card.append(name);
  let phone = null;
  if (state.requirePhone) {
    phone = el('input');
    phone.type = 'tel';
    phone.placeholder = t('detailsPhone');
    phone.maxLength = 32;
    phone.autocomplete = 'tel';
    phone.value = state.detailsDraft.phone;
    phone.dataset.field = 'details-phone';
    phone.addEventListener('input', () => (state.detailsDraft.phone = phone.value));
    card.append(phone);
  }
  const save = el('button', 'primary small', t('detailsSave'));
  save.type = 'submit';
  const error = el('p', 'error hidden');
  card.append(save, error);
  card.addEventListener('submit', async (event) => {
    event.preventDefault();
    const displayName = name.value.trim();
    if (!displayName) return;
    if (preview) {
      state.justNamed = displayName;
      return renderCards();
    }
    save.disabled = true;
    try {
      const data = await api('/api/chat/public/profile', {
        method: 'POST',
        body: JSON.stringify({ displayName, ...(phone?.value.trim() ? { phone: phone.value.trim() } : {}) }),
      });
      state.visitor = data.visitor;
      state.justNamed = displayName;
      state.detailsDraft = { name: '', phone: '' };
      renderCards();
    } catch (failure) {
      save.disabled = false;
      showError(error, failure.message);
    }
  });
  return card;
}

/**
 * The faces. Tapping one saves it at once — the face *is* the answer, and a visitor who taps and
 * closes the panel has still told the business something — and then offers a box for more, which
 * is sent as a second answer to the same question rather than as a separate thing.
 */
function ratingCard(conversation) {
  const id = conversation.id;
  const stage = state.ratingStage[id] ?? (conversation.rating ? 'done' : 'faces');
  const card = el('div', `card-in-thread rating${freshCard(`rating-${id}`)}`);
  card.append(el('strong', '', stage === 'faces' ? t('ratePrompt') : t('rated')));

  const row = el('div', 'face-row');
  row.setAttribute('role', 'group');
  row.setAttribute('aria-label', t('ratePrompt'));
  for (const score of RATINGS) {
    const button = el('button', 'rate');
    button.type = 'button';
    button.setAttribute('aria-label', t(`rating${score}`));
    button.title = t(`rating${score}`);
    button.setAttribute('aria-pressed', String(conversation.rating?.score === score));
    button.disabled = !conversation.ratable && !preview;
    button.append(icon(`${FACES}#rating-${score}`, 'rate-face'));
    button.addEventListener('click', () => void rate(conversation, score));
    row.append(button);
  }
  card.append(row);
  if (conversation.rating && RATINGS.includes(conversation.rating.score)) {
    card.append(el('span', 'rate-label', t(`rating${conversation.rating.score}`)));
  }

  if (stage === 'comment') {
    const form = el('form', 'rate-more');
    const box = el('textarea');
    box.rows = 2;
    box.maxLength = 1000;
    box.placeholder = t('tellUsMore');
    box.dir = 'auto';
    box.value = state.ratingDraft[id] ?? '';
    box.dataset.field = 'rate-comment';
    box.addEventListener('input', () => (state.ratingDraft[id] = box.value));
    const actions = el('div', 'rate-actions');
    const skip = el('button', 'link-quiet', t('skip'));
    skip.type = 'button';
    skip.addEventListener('click', () => {
      state.ratingStage[id] = 'done';
      renderCards();
    });
    const sendIt = el('button', 'primary small', t('send'));
    sendIt.type = 'submit';
    actions.append(skip, sendIt);
    form.append(box, actions);
    form.addEventListener('submit', (event) => {
      event.preventDefault();
      void rate(conversation, conversation.rating?.score, box.value.trim());
    });
    card.append(form);
    // Focused when it first appears — the next thing they will do is type, if anything.
    if (state.ratingFocus === id) {
      state.ratingFocus = null;
      requestAnimationFrame(() => box.focus({ preventScroll: true }));
    }
  } else if (conversation.rating?.comment) {
    const quote = el('p', 'quote', conversation.rating.comment);
    quote.dir = 'auto';
    card.append(quote);
  }
  const error = el('p', 'error hidden');
  error.id = `rateError-${id}`;
  card.append(error);
  return card;
}

async function rate(conversation, score, comment) {
  if (!score) return;
  const id = conversation.id;
  const before = conversation.rating;
  const withComment = comment !== undefined;
  // Drawn before the server answers: the face they tapped lights up the moment they tap it.
  conversation.rating = {
    score,
    comment: withComment ? comment || null : (before?.comment ?? null),
    ratedAt: new Date().toISOString(),
  };
  state.ratingStage[id] = withComment ? 'done' : 'comment';
  if (!withComment) state.ratingFocus = id;
  if (withComment) delete state.ratingDraft[id];
  renderCards();
  if (preview) return;
  try {
    const data = await api(`/api/chat/public/conversations/${encodeURIComponent(id)}/rating`, {
      method: 'POST',
      body: JSON.stringify({ score, ...(withComment ? { comment } : {}) }),
    });
    Object.assign(conversation, data.conversation);
    syncRow(conversation, []);
    renderCards();
  } catch (error) {
    conversation.rating = before;
    state.ratingStage[id] = before ? 'done' : 'faces';
    renderCards();
    const holder = document.getElementById(`rateError-${id}`);
    if (holder) showError(holder, error.message);
  }
}

/* ── Sending ──────────────────────────────────────────────────────────── */

async function send(event) {
  event?.preventDefault();
  const input = $('input');
  const body = input.value.trim();
  const conversation = state.current?.conversation ?? null;
  if (!isLive(conversation)) return;
  // A file still on its way holds the message back — the send button says so while it does.
  if (state.uploads.some((upload) => upload.status === 'uploading')) return;
  const files = state.uploads.filter((upload) => upload.status === 'done');
  if (!body && !files.length) return;
  /* Nothing leaves the preview. Clearing the box is the honest response: it shows the composer
     works without inventing a conversation on the client's account. */
  if (preview) {
    input.value = '';
    autoGrow();
    return;
  }
  input.value = '';
  autoGrow();
  closeEmoji();
  clearError($('chatError'));

  const clientKey = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  state.pending.set(clientKey, {
    clientKey,
    body,
    // The picture they sent, from their own device, while the server's copy is on its way.
    attachments: files.map((upload) => ({ ...upload.attachment, localUrl: upload.url })),
    createdAt: new Date().toISOString(),
    pending: true,
  });
  // Sent, so out of the tray — but their blob URLs live on as the bubble's pictures.
  clearUploads({ keepUrls: true });
  renderThread();

  // A new conversation is started by its first message: there is nobody to send it to until then.
  if (!conversation) {
    try {
      await ensureSession();
    } catch (error) {
      const item = state.pending.get(clientKey);
      state.pending.set(clientKey, { ...item, pending: false, failed: true });
      renderThread();
      showError(
        $('chatError'),
        error.status === 429 ? t('tooManyChats') : error.message,
      );
      return;
    }
  }
  await deliver(clientKey);
}

async function deliver(clientKey) {
  const item = state.pending.get(clientKey);
  if (!item) return;
  try {
    const message = await api('/api/chat/public/messages', {
      method: 'POST',
      body: JSON.stringify({
        body: item.body,
        clientKey,
        ...(item.replyId ? { replyId: item.replyId } : {}),
        ...(item.attachments?.length
          ? { attachmentIds: item.attachments.map((attachment) => attachment.id) }
          : {}),
      }),
    });
    state.pending.delete(clientKey);
    addMessage(message);
    // The first thing they say is what makes the name card worth showing.
    renderCards();
  } catch (error) {
    if (error.status === 409) {
      state.pending.delete(clientKey);
      return void closedUnderneath();
    }
    state.pending.set(clientKey, { ...item, pending: false, failed: true });
    renderThread();
    showError(
      $('chatError'),
      error.status === 429 ? t('slowDown') : error.message,
    );
  }
}

async function resend(clientKey) {
  const item = state.pending.get(clientKey);
  if (!item) return;
  state.pending.set(clientKey, { ...item, pending: true, failed: false });
  renderThread();
  if (!state.current?.conversation) {
    try {
      await ensureSession();
    } catch {
      state.pending.set(clientKey, { ...item, pending: false, failed: true });
      return renderThread();
    }
  }
  await deliver(clientKey);
}

/* ── Files ────────────────────────────────────────────────────────────── */

/** The web channel's own limits — the server holds the same ones (`refuseToSend`). */
const MAX_FILES = 10;
const MAX_BYTES = 50 * 1024 * 1024;

/** Whether a file can be added right now: offered, on a conversation that can still be written in. */
const canAttach = () =>
  !preview &&
  state.widget.chat.allowAttachments &&
  state.screen === 'chat' &&
  isLive(state.current?.conversation ?? null) &&
  !$('composer').classList.contains('hidden');

/** Files from the paperclip, a drop or a paste: checked, put in the tray, and sent up at once. */
function addFiles(list) {
  const files = [...(list ?? [])];
  if (!files.length || !canAttach()) return;
  clearError($('chatError'));
  for (const file of files) {
    if (state.uploads.length >= MAX_FILES) {
      showError($('chatError'), t('tooMany'));
      break;
    }
    const name = file.name || t('attachment');
    if (file.size > MAX_BYTES) {
      showError($('chatError'), t('tooBig', { name }));
      continue;
    }
    const upload = {
      key: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      file,
      name,
      image: (file.type || '').startsWith('image/'),
      url: URL.createObjectURL(file),
      progress: 0,
      status: 'uploading',
      attachment: null,
      xhr: null,
      node: null,
    };
    state.uploads.push(upload);
    void sendFile(upload);
  }
  renderUploads();
  $('input').focus({ preventScroll: true });
}

/**
 * One file up, with progress. An XMLHttpRequest rather than `fetch` because only it reports upload
 * progress, and a photo over a phone's data is long enough to need a bar.
 */
async function sendFile(upload) {
  try {
    await ensureSession();
  } catch (error) {
    return failUpload(upload, error.status === 429 ? t('tooManyChats') : error.message);
  }
  // Taken back out of the tray while the conversation was starting.
  if (!state.uploads.includes(upload)) return;
  const xhr = new XMLHttpRequest();
  upload.xhr = xhr;
  xhr.open('POST', '/api/chat/public/attachments');
  xhr.setRequestHeader('x-chat-token', state.token);
  xhr.upload.addEventListener('progress', (event) => {
    if (!event.lengthComputable) return;
    upload.progress = event.loaded / event.total;
    renderUploads();
  });
  xhr.addEventListener('load', () => {
    let data = {};
    try {
      data = JSON.parse(xhr.responseText || '{}');
    } catch {
      /* Not JSON — a proxy's error page. Said below in our own words. */
    }
    if (xhr.status === 201 && data.attachment?.id) {
      upload.status = 'done';
      upload.progress = 1;
      upload.attachment = data.attachment;
      // The server's copy, when it comes back on the message, is drawn from the file on this device.
      fileUrls.set(data.attachment.id, upload.url);
      return renderUploads();
    }
    if (xhr.status === 409) return void closedUnderneath();
    failUpload(
      upload,
      typeof data.error === 'string' ? data.error : t('uploadFailed', { name: upload.name }),
    );
  });
  xhr.addEventListener('error', () => failUpload(upload, t('uploadFailed', { name: upload.name })));
  const form = new FormData();
  form.append('file', upload.file, upload.name);
  xhr.send(form);
}

function failUpload(upload, message) {
  if (!state.uploads.includes(upload)) return;
  upload.status = 'failed';
  showError($('chatError'), message);
  renderUploads();
}

function removeUpload(upload) {
  upload.xhr?.abort();
  URL.revokeObjectURL(upload.url);
  state.uploads = state.uploads.filter((one) => one !== upload);
  renderUploads();
}

/** Empties the tray. `keepUrls` when the files were just sent and their pictures are in a bubble. */
function clearUploads({ keepUrls = false } = {}) {
  for (const upload of state.uploads) {
    if (upload.status === 'uploading') upload.xhr?.abort();
    if (!keepUrls || upload.status !== 'done') URL.revokeObjectURL(upload.url);
  }
  state.uploads = [];
  renderUploads();
}

/**
 * The tray above the box. Each chip is built once and then only updated — this runs on every
 * progress event, and rebuilding a thumbnail that often would flicker it.
 */
function renderUploads() {
  const tray = $('uploads');
  for (const upload of state.uploads) {
    if (!upload.node) upload.node = uploadChip(upload);
    upload.node.classList.toggle('failed', upload.status === 'failed');
    upload.node.querySelector('.progress').style.width =
      upload.status === 'uploading' ? `${Math.max(6, Math.round(upload.progress * 100))}%` : '0';
    upload.node.setAttribute('aria-busy', String(upload.status === 'uploading'));
  }
  tray.replaceChildren(...state.uploads.map((upload) => upload.node));
  tray.classList.toggle('hidden', state.uploads.length === 0);
  const busy = state.uploads.some((upload) => upload.status === 'uploading');
  $('send').disabled = busy;
  $('send').setAttribute('aria-label', busy ? t('uploading') : t('send'));
}

function uploadChip(upload) {
  const chip = el('div', 'upload');
  chip.setAttribute('role', 'listitem');
  let thumb;
  if (upload.image) {
    thumb = document.createElement('img');
    thumb.className = 'thumb';
    thumb.alt = '';
    thumb.src = upload.url;
  } else if (upload.name.includes('.')) {
    thumb = el('span', 'thumb', upload.name.split('.').pop().slice(0, 4));
  } else {
    thumb = el('span', 'thumb');
    thumb.append(icon('#c-paperclip'));
  }
  const name = el('span', 'name', upload.name);
  name.dir = 'auto';
  name.title = upload.name;
  const remove = el('button', 'remove');
  remove.type = 'button';
  remove.setAttribute('aria-label', t('removeFile', { name: upload.name }));
  remove.append(icon('#c-x'));
  remove.addEventListener('click', () => removeUpload(upload));
  chip.append(thumb, name, el('i', 'progress'), remove);
  return chip;
}

/** A message for the conversation on screen, or news for the list when it is for another. */
function addMessage(message) {
  if (!message?.id) return;
  if (message.clientKey) state.pending.delete(message.clientKey);
  const live = liveConversation();
  const current = state.current?.conversation;
  const onScreen = state.screen === 'chat' && current && (!live || current.id === live.id);
  if (onScreen && state.current) {
    if (!state.current.messages.some((existing) => existing.id === message.id)) {
      state.current.messages.push(message);
      state.current.messages.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
    }
    syncRow(current, state.current.messages);
    // The header and the thread, not the cards: a reply arriving must not rebuild the name card
    // under somebody who is halfway through typing their name into it.
    renderHeader();
    renderThread();
    if (document.visibilityState === 'visible') markSeen(current);
    return;
  }
  if (live) syncRow(live, [message]);
  if (state.screen === 'home') renderHome();
  if (state.screen === 'messages') renderMessages();
}

/* ── The socket ───────────────────────────────────────────────────────── */

/**
 * Subscribed to the live conversation, wherever the visitor is — Home, the list, an old chat — so
 * a reply turns into a dot on the Messages tab and a count on the launcher outside rather than
 * waiting for them to wander back.
 */
function connectSocket() {
  if (preview) return;
  if (state.socket && state.socket.readyState <= WebSocket.OPEN) return;
  if (!state.token || !liveConversation()) return;
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(`${protocol}//${location.host}/ws/chat`);
  state.socket = socket;

  socket.onopen = () => socket.send(JSON.stringify({ type: 'auth', token: state.token }));
  socket.onmessage = (event) => {
    let frame;
    try {
      frame = JSON.parse(event.data);
    } catch {
      return;
    }
    if (frame.type === 'authenticated') {
      state.reconnectAttempt = 0;
      void resync();
      return;
    }
    if (frame.type === 'chat.message') {
      clearTyping();
      const message = frame.payload?.message;
      // The finished reply takes the draft's place.
      if (message?.speaker === 'AI') state.draft = null;
      addMessage(message);
      if (embedded && !panelOpen && message?.speaker !== 'CALLER') {
        unseen += 1;
        toHost({ type: 'cnct:unread', count: unseen });
        /**
         * Who and what, for the card the launcher draws above the bubble — a name and the first
         * lines, nothing else. Same caveat as the count: the parent is a page we do not know, so
         * this carries only what the visitor would see the moment they opened the panel.
         */
        const body = wordsOf(message).replace(/\s+/g, ' ').trim();
        toHost({
          type: 'cnct:preview',
          message: {
            name: message.sender?.name || state.business,
            ai: message.speaker === 'AI',
            body: (body || t('sentAFile')).slice(0, 200),
          },
        });
      }
      return;
    }
    if (frame.type === 'chat.typing') return showTyping();
    if (frame.type === 'chat.draft') return showDraft(frame.payload ?? {});
    if (frame.type === 'chat.read') return seenByTeam(frame.payload?.at);
    if (frame.type === 'chat.conversation.updated') return updated(frame.payload ?? {});
    if (frame.type === 'chat.closed') return void closedUnderneath();
  };
  socket.onclose = () => {
    if (state.socket !== socket) return;
    state.socket = null;
    if (!liveConversation()) return;
    const delay = Math.min(15_000, 1000 * 2 ** state.reconnectAttempt++);
    setTimeout(connectSocket, delay);
  };
}

/** Somebody on the team read the conversation: "Seen" under what the visitor last wrote. */
function seenByTeam(at) {
  const live = liveConversation();
  if (!live || typeof at !== 'string') return;
  live.seenAt = at;
  if (state.current?.conversation?.id === live.id) {
    state.current.conversation.seenAt = at;
    if (state.screen === 'chat') renderThread();
  }
}

/** Who is on it changed — a person took over, or handed back. */
function updated(payload) {
  const live = liveConversation();
  if (!live) return;
  const patch = {
    ...(payload.status ? { status: payload.status } : {}),
    ...('withPerson' in payload ? { withPerson: payload.withPerson } : {}),
    ...('assignee' in payload ? { assignee: payload.assignee } : {}),
  };
  Object.assign(live, patch);
  if (state.current?.conversation?.id === live.id) {
    Object.assign(state.current.conversation, patch);
    if (state.screen === 'chat') renderHeader();
  }
}

/**
 * The conversation ended while they were in it — an operator closed it, the inbox's timer did, or
 * they pressed End. The thread stays where it is and grows the faces at the bottom; the list learns
 * the server's view of it (whether it can be rated) a moment later.
 */
async function closedUnderneath() {
  const live = liveConversation();
  const now = new Date().toISOString();
  for (const conversation of [live, state.current?.conversation]) {
    if (!conversation) continue;
    conversation.status = 'CLOSED';
    conversation.closedAt = conversation.closedAt ?? now;
  }
  state.socket?.close();
  state.socket = null;
  state.pending.clear();
  clearUploads();
  clearTyping();
  if (state.screen === 'chat') renderChat();
  try {
    const id = state.current?.conversation?.id;
    await refreshConversations();
    if (id && state.screen === 'chat' && state.current?.conversation?.id === id) {
      const fresh = state.conversations.find((one) => one.id === id);
      if (fresh) Object.assign(state.current.conversation, fresh);
      renderChat();
    }
  } catch {
    /* The faces wait for the next load; the thread is already right. */
  }
}

/** After a reconnect: the socket replays nothing, so ask for the truth again. */
async function resync() {
  try {
    await refreshConversations();
    const id = state.current?.conversation?.id;
    if (state.screen === 'chat' && id) {
      const data = await api(`/api/chat/public/conversations/${encodeURIComponent(id)}`);
      if (state.current?.conversation?.id !== id) return;
      state.current = { conversation: data.conversation, messages: data.messages ?? [] };
      for (const message of state.current.messages) {
        if (message.clientKey) state.pending.delete(message.clientKey);
      }
      renderChat();
    }
  } catch {
    /* The next reconnect, or the next visibility change, tries again. */
  }
}

/**
 * The reply so far. Each frame carries the whole text, so a frame that replaces what was shown (a
 * guardrail rewriting it) needs nothing special, and `done` takes it down without a message, which
 * is what happens when a person is taking over instead.
 */
function showDraft(draft) {
  if (draft.done) {
    if (state.draft?.id !== draft.id) return;
    state.draft = null;
  } else {
    state.draft = { id: draft.id, body: String(draft.body ?? ''), status: draft.status ?? '' };
    // The draft is the sign of life now; the dots would only sit under it.
    clearTimeout(state.typingTimer);
    state.typingTimer = null;
  }
  if (state.screen === 'chat') renderThread();
}

function showTyping() {
  const showing = Boolean(state.typingTimer);
  clearTimeout(state.typingTimer);
  state.typingTimer = setTimeout(clearTyping, 5000);
  if (!showing && state.screen === 'chat') renderThread();
}
function clearTyping() {
  const showing = Boolean(state.typingTimer);
  clearTimeout(state.typingTimer);
  state.typingTimer = null;
  if (showing && state.screen === 'chat') renderThread();
}

function sendTyping() {
  const now = Date.now();
  if (now - state.lastTypingSentAt < 3000) return;
  if (state.socket?.readyState !== WebSocket.OPEN) return;
  state.lastTypingSentAt = now;
  state.socket.send(JSON.stringify({ type: 'typing' }));
}

/* ── Ending ───────────────────────────────────────────────────────────── */

const isConfirming = () => !$('endConfirm').classList.contains('hidden');

function closeConfirm() {
  $('endConfirm').classList.add('hidden');
}

async function endChat() {
  closeConfirm();
  if (preview) {
    state.current = { conversation: PREVIEW.closed, messages: PREVIEW.liveMessages };
    return renderChat();
  }
  try {
    await api('/api/chat/public/close', { method: 'POST' });
  } catch {
    /* Ending is the visitor's way out; it should never be the thing that fails. */
  }
  await closedUnderneath();
}

function autoGrow() {
  const input = $('input');
  input.style.height = 'auto';
  input.style.height = `${Math.min(input.scrollHeight, 140)}px`;
}

/* ── Emoji ────────────────────────────────────────────────────────────── */

/**
 * **The emoji tray: the eight people send a business, then every other one, searchable.**
 *
 * It was the eight alone, on the argument that a full keyboard is a dependency, a search index and a
 * megabyte of images on a page a stranger loads over their phone data. None of that holds for this
 * one. The emoji are drawn by the visitor's own font, and the list — names and keywords from
 * Unicode's own data, the same list the console's picker offers, generated by
 * `web/scripts/emoji-catalogue.mjs` — is 32 kB gzipped and fetched the first time the tray opens,
 * not before. The eight are there at once and the rest arrives under them. Searched in English and
 * Arabic ("thanks" and "شكرا" both find 🙏): the Arabic words are a second file, fetched for an
 * Arabic widget or the first search typed in Arabic.
 *
 * The ranking is the console's (`web/lib/emoji.ts`), kept the same so that a word finds the same
 * emoji on both sides of a conversation.
 */
const emoji = {
  /** `[{ key, emoji: [{ char, name, named, keywords, words }] }]` once loaded. */
  groups: null,
  byChar: new Map(),
  /** Arabic name and words by emoji, once loaded. */
  arabic: null,
  loading: null,
  arabicLoading: null,
  failed: false,
  /** The language the tray was drawn in, or null before it has been. */
  builtIn: null,
};
const EMOJI_RECENT_KEY = 'cnct_emoji_recent';
const EMOJI_RECENT_MAX = 16;
const EMOJI_TABS = {
  smileys: '😀',
  people: '👋',
  nature: '🐻',
  food: '🍔',
  travel: '✈️',
  activities: '⚽',
  objects: '💡',
  symbols: '❤️',
  flags: '🏳️',
};
/** Spelled out key by key, so the strings test can see every one of them is in both tables. */
const emojiGroupLabel = (key) =>
  ({
    recent: t('emojiRecent'),
    smileys: t('emojiSmileys'),
    people: t('emojiPeople'),
    nature: t('emojiNature'),
    food: t('emojiFood'),
    travel: t('emojiTravel'),
    activities: t('emojiActivities'),
    objects: t('emojiObjects'),
    symbols: t('emojiSymbols'),
    flags: t('emojiFlags'),
  })[key] ?? key;
/** First among equals in a search — the console's list, word for word. */
const COMMON_EMOJI = new Set([
  '👍', '🙏', '❤️', '😊', '🙂', '😀', '😂', '🎉', '👋', '👌', '👏', '✅', '🌹', '💐', '🤝', '😍',
  '😢', '😮', '🔥', '💯', '✨', '⭐', '☕', '🌙',
]);

/** Lower case; in Arabic without the short vowels, and with one alef, teh marbuta and yeh. */
const foldEmoji = (text) =>
  text
    .toLowerCase()
    .replace(/[\u064B-\u065F\u0670\u0640]/g, '')
    .replace(/[\u0623\u0625\u0622\u0671]/g, '\u0627')
    .replace(/\u0629/g, '\u0647')
    .replace(/\u0649/g, '\u064A');

const emojiWords = (name, keywords) => {
  const split = (text) => foldEmoji(text).split(/\s+/).filter(Boolean);
  return { named: split(name), keywords: split(keywords), words: foldEmoji(`${name} ${keywords}`) };
};

const fetchJson = (url) =>
  fetch(url).then((response) => {
    if (!response.ok) throw new Error(`${url} answered ${response.status}`);
    return response.json();
  });

function loadEmoji() {
  if (!emoji.loading) {
    emoji.failed = false;
    emoji.loading = fetchJson('/chat-assets/emoji-catalogue.json')
      .then((groups) => {
        emoji.groups = groups.map(({ key, emoji: list }) => ({
          key,
          emoji: list.map(([char, name, keywords]) => ({ char, name, ...emojiWords(name, keywords) })),
        }));
        emoji.byChar = new Map(emoji.groups.flatMap((group) => group.emoji.map((one) => [one.char, one])));
      })
      .catch(() => {
        // The eight still work; the next opening tries again.
        emoji.loading = null;
        emoji.failed = true;
      });
  }
  return emoji.loading;
}

function loadArabicEmoji() {
  if (!emoji.arabicLoading) {
    emoji.arabicLoading = fetchJson('/chat-assets/emoji-arabic.json')
      .then((words) => {
        emoji.arabic = {};
        for (const [char, [name = '', keywords = '']] of Object.entries(words)) {
          emoji.arabic[char] = { name, ...emojiWords(name, keywords) };
        }
      })
      .catch(() => {
        emoji.arabicLoading = null;
      });
  }
  return emoji.arabicLoading;
}

const isArabicText = (text) => /[\u0600-\u06FF]/.test(text);

/** A whole word of the name, a whole keyword, the start of a word of either, then anywhere. */
const emojiFit = (term, { named, keywords }) =>
  named.includes(term)
    ? 0
    : keywords.includes(term)
      ? 1
      : named.some((word) => word.startsWith(term))
        ? 2
        : keywords.some((word) => word.startsWith(term))
          ? 3
          : 4;

function searchEmoji(query) {
  const terms = foldEmoji(query).split(/\s+/).filter(Boolean);
  if (!terms.length || !emoji.groups) return [];
  const ranked = [];
  let order = 0;
  for (const group of emoji.groups) {
    for (const one of group.emoji) {
      order += 1;
      const arabic = emoji.arabic?.[one.char];
      const words = arabic ? `${one.words} ${arabic.words}` : one.words;
      if (!terms.every((term) => words.includes(term))) continue;
      const rank = terms.reduce(
        (sum, term) => sum + Math.min(emojiFit(term, one), arabic ? emojiFit(term, arabic) : 4),
        0,
      );
      ranked.push({ one, rank, order });
    }
  }
  return ranked
    .sort(
      (a, b) =>
        a.rank - b.rank ||
        Number(COMMON_EMOJI.has(b.one.char)) - Number(COMMON_EMOJI.has(a.one.char)) ||
        a.order - b.order,
    )
    .map(({ one }) => one.char);
}

const recentEmoji = () => {
  try {
    const stored = JSON.parse(storage.get(EMOJI_RECENT_KEY) ?? '[]');
    return Array.isArray(stored) ? stored.filter((one) => typeof one === 'string') : [];
  } catch {
    return [];
  }
};
const rememberEmoji = (char) =>
  storage.set(
    EMOJI_RECENT_KEY,
    JSON.stringify([char, ...recentEmoji().filter((one) => one !== char)].slice(0, EMOJI_RECENT_MAX)),
  );
/** The visitor's own, newest first, topped up with the eight until they have sent that many. */
const frequentEmoji = () => [...new Set([...recentEmoji(), ...QUICK_EMOJI])].slice(0, EMOJI_RECENT_MAX);

const emojiName = (char) =>
  (lang === 'ar' && emoji.arabic?.[char]?.name) || emoji.byChar.get(char)?.name || char;

function emojiGrid(chars, label) {
  const grid = el('div', 'emoji-grid');
  grid.setAttribute('role', 'group');
  grid.setAttribute('aria-label', label);
  for (const char of chars) {
    const button = el('button', '', char);
    button.type = 'button';
    button.dataset.emoji = char;
    button.tabIndex = -1;
    button.setAttribute('aria-label', emojiName(char));
    button.title = emojiName(char);
    grid.append(button);
  }
  return grid;
}

function emojiSection(key, chars) {
  const section = el('div', 'emoji-section');
  section.dataset.section = key;
  section.append(el('p', 'emoji-heading', emojiGroupLabel(key)), emojiGrid(chars, emojiGroupLabel(key)));
  return section;
}

/** The buttons showing now — the sections or the results, whichever is not hidden. */
const shownEmoji = () =>
  [...$('emojiTray').querySelectorAll('.emoji-scroll > :not(.hidden) button[data-emoji]')];

/** One tab stop for the whole tray: the first emoji showing. The arrows do the rest. */
function rovingStop() {
  shownEmoji().forEach((button, index) => {
    button.tabIndex = index === 0 ? 0 : -1;
  });
}

/**
 * What the scroll area shows: the sections, or what a search found.
 *
 * The sections are drawn once, when the list arrives; after that only the visitor's own row is
 * redrawn, after each pick. Drawing all nineteen hundred again would cost a phone a frame for
 * nothing and throw away where the visitor had scrolled to.
 */
function renderEmoji() {
  const tray = $('emojiTray');
  const sections = tray.querySelector('.emoji-sections');
  const results = tray.querySelector('.emoji-results');
  const query = tray.querySelector('.emoji-search').value.trim();
  sections.classList.toggle('hidden', Boolean(query));
  results.classList.toggle('hidden', !query);
  tray.querySelector('.emoji-tabs').classList.toggle('hidden', Boolean(query) || !emoji.groups);
  if (query) {
    const found = searchEmoji(query).slice(0, 120);
    results.replaceChildren(
      found.length
        ? emojiGrid(found, t('searchEmoji'))
        : !emoji.groups
          ? el('p', 'emoji-note', emoji.failed ? t('emojiFailed') : t('emojiLoading'))
          : // Isolated, so an Arabic search in an English sentence keeps its quotes where they belong.
            el('p', 'emoji-note', t('noEmoji', { query: `\u2068${query}\u2069` })),
    );
  } else {
    sections.querySelector('[data-section="recent"]')?.remove();
    sections.prepend(emojiSection('recent', frequentEmoji()));
    sections.querySelector('.emoji-note')?.remove();
    if (emoji.groups && !sections.querySelector('[data-section="smileys"]')) {
      for (const group of emoji.groups) {
        sections.append(
          emojiSection(
            group.key,
            group.emoji.map((one) => one.char),
          ),
        );
      }
    } else if (emoji.failed) {
      sections.append(el('p', 'emoji-note', t('emojiFailed')));
    }
  }
  rovingStop();
  litTab();
}

/** Names the emoji in Arabic once the Arabic words arrive, for a widget that speaks it. */
function relabelEmoji() {
  if (lang !== 'ar') return;
  for (const button of $('emojiTray').querySelectorAll('button[data-emoji]')) {
    button.setAttribute('aria-label', emojiName(button.dataset.emoji));
    button.title = emojiName(button.dataset.emoji);
  }
}

/** Which section the top of the scroll area is in, so its tab is the lit one. */
function litTab() {
  const tray = $('emojiTray');
  const scroll = tray.querySelector('.emoji-scroll');
  const top = scroll.getBoundingClientRect().top;
  let at = 'recent';
  for (const section of scroll.querySelectorAll('.emoji-section')) {
    if (section.getBoundingClientRect().top - top <= 4) at = section.dataset.section;
  }
  for (const tab of tray.querySelectorAll('.emoji-tabs button')) {
    tab.setAttribute('aria-selected', String(tab.dataset.tab === at));
  }
}

function buildEmojiTray() {
  const tray = $('emojiTray');
  const search = el('input', 'emoji-search');
  search.type = 'search';
  search.dir = 'auto';
  search.placeholder = t('searchEmoji');
  search.setAttribute('aria-label', t('searchEmoji'));
  search.addEventListener('input', () => {
    if (isArabicText(search.value) && !emoji.arabic) {
      void loadArabicEmoji().then(() => {
        relabelEmoji();
        renderEmoji();
      });
    }
    renderEmoji();
  });
  search.addEventListener('keydown', (event) => {
    if (event.isComposing) return;
    if (event.key === 'Enter') {
      // Never the form's submit: Enter here picks, it does not send.
      event.preventDefault();
      const [first] = shownEmoji();
      if (first && search.value.trim()) pickEmoji(first.dataset.emoji);
    } else if (event.key === 'ArrowDown') {
      event.preventDefault();
      shownEmoji()[0]?.focus();
    }
  });

  const tabs = el('div', 'emoji-tabs hidden');
  tabs.setAttribute('role', 'tablist');
  tabs.setAttribute('aria-label', t('emoji'));
  for (const key of ['recent', ...Object.keys(EMOJI_TABS)]) {
    const tab = el('button', '', key === 'recent' ? '🕘' : EMOJI_TABS[key]);
    tab.type = 'button';
    tab.dataset.tab = key;
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-label', emojiGroupLabel(key));
    tab.title = emojiGroupLabel(key);
    tab.addEventListener('mousedown', (event) => event.preventDefault());
    tab.addEventListener('click', () => {
      const scroll = tray.querySelector('.emoji-scroll');
      const section = scroll.querySelector(`[data-section="${key}"]`);
      if (!section) return;
      scroll.scrollTop += section.getBoundingClientRect().top - scroll.getBoundingClientRect().top;
      litTab();
    });
    tabs.append(tab);
  }

  const scroll = el('div', 'emoji-scroll');
  scroll.addEventListener('scroll', litTab, { passive: true });
  // The box keeps its caret while an emoji is pressed, so the emoji goes where the visitor stopped.
  scroll.addEventListener('mousedown', (event) => {
    if (event.target.closest('button[data-emoji]')) event.preventDefault();
  });
  scroll.addEventListener('click', (event) => {
    const button = event.target.closest('button[data-emoji]');
    if (button) pickEmoji(button.dataset.emoji);
  });
  /* The arrows move through the grid the way they move through a phone's keyboard; up from the
     first row goes back to the search. */
  scroll.addEventListener('keydown', (event) => {
    const here = document.activeElement;
    if (!(here instanceof HTMLButtonElement) || !here.dataset.emoji) return;
    const buttons = shownEmoji();
    const at = buttons.indexOf(here);
    const style = getComputedStyle(here.parentElement);
    const columns = style.gridTemplateColumns.split(' ').length || 1;
    const back = style.direction === 'rtl' ? 1 : -1;
    const step = { ArrowRight: -back, ArrowLeft: back, ArrowDown: columns, ArrowUp: -columns }[event.key];
    if (!step || at < 0) return;
    event.preventDefault();
    if (at + step < 0) return void search.focus();
    buttons[Math.min(at + step, buttons.length - 1)]?.focus();
  });

  scroll.append(el('div', 'emoji-sections'), el('div', 'emoji-results hidden'));
  tray.replaceChildren(search, tabs, scroll);
  // A property rather than a listener, because the tray is drawn again when the language changes.
  tray.onkeydown = (event) => {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    closeEmoji();
    $('input').focus({ preventScroll: true });
  };
  emoji.builtIn = lang;
}

/** Into the box where the caret was, over anything selected — the way a typed letter would go. */
function pickEmoji(char) {
  const input = $('input');
  const start = input.selectionStart ?? input.value.length;
  const end = input.selectionEnd ?? start;
  if (input.value.length - (end - start) + char.length > input.maxLength) return;
  input.setRangeText(char, start, end, 'end');
  autoGrow();
  sendTyping();
  rememberEmoji(char);
  const search = $('emojiTray').querySelector('.emoji-search');
  if (search.value) search.value = '';
  renderEmoji();
  // On a phone, focusing the box would raise the keyboard over the tray the visitor is using.
  if (typesWithAKeyboard()) input.focus({ preventScroll: true });
}

function toggleEmoji(open = $('emojiTray').classList.contains('hidden')) {
  const tray = $('emojiTray');
  if (!open) return closeEmoji();
  // Drawn in the language it was first opened in — again if the builder's preview has switched since.
  if (emoji.builtIn !== lang) buildEmojiTray();
  tray.classList.remove('hidden');
  $('emojiButton').setAttribute('aria-expanded', 'true');
  renderEmoji();
  if (!emoji.groups) void loadEmoji().then(renderEmoji);
  if (lang === 'ar' && !emoji.arabic) {
    void loadArabicEmoji().then(() => {
      relabelEmoji();
      renderEmoji();
    });
  }
  if (typesWithAKeyboard()) tray.querySelector('.emoji-search').focus({ preventScroll: true });
}

function closeEmoji() {
  $('emojiTray').classList.add('hidden');
  $('emojiButton').setAttribute('aria-expanded', 'false');
}

/* ── Wiring ───────────────────────────────────────────────────────────── */

$('homeAll').addEventListener('click', () => goTab('messages'));
$('messagesBack').addEventListener('click', () => goTab('home'));
$('homeStart').addEventListener('click', () => startNew(''));
$('messagesStart').addEventListener('click', () => startNew(''));
$('closedStart').addEventListener('click', () => startNew(''));
$('introBack').addEventListener('click', () => {
  state.opener = '';
  goTab(state.widget.home.enabled ? state.tab : 'messages');
});
$('chatBack').addEventListener('click', () => {
  closeConfirm();
  state.justNamed = '';
  goTab(state.widget.home.enabled ? state.tab : 'messages');
  if (!preview) void refreshConversations().catch(() => {});
});

$('emojiButton').addEventListener('click', () => toggleEmoji());

$('attachButton').addEventListener('click', () => {
  if (canAttach()) $('fileInput').click();
});
$('fileInput').addEventListener('change', () => {
  addFiles($('fileInput').files);
  // Cleared, so picking the same file again is still a change.
  $('fileInput').value = '';
});
/* A screenshot pasted into the box is a file. Text that also came with a picture of itself — a
   copy from a word processor — is text. */
$('input').addEventListener('paste', (event) => {
  const files = [...(event.clipboardData?.files ?? [])];
  if (!files.length || !canAttach() || event.clipboardData.getData('text/plain')) return;
  event.preventDefault();
  addFiles(files);
});

/* Dropped anywhere on the conversation. Counted in and out, because `dragleave` fires for every
   child the pointer crosses. Elsewhere on the page a dropped file is refused rather than opened —
   an iframe that navigates to the file is a chat that is gone. */
const draggingFiles = (event) => [...(event.dataTransfer?.types ?? [])].includes('Files');
let dragDepth = 0;
$('chat').addEventListener('dragenter', (event) => {
  if (!draggingFiles(event) || !canAttach()) return;
  event.preventDefault();
  dragDepth += 1;
  $('dropZone').classList.remove('hidden');
});
$('chat').addEventListener('dragleave', (event) => {
  if (!draggingFiles(event)) return;
  dragDepth = Math.max(0, dragDepth - 1);
  if (dragDepth === 0) $('dropZone').classList.add('hidden');
});
$('chat').addEventListener('drop', (event) => {
  if (!draggingFiles(event)) return;
  event.preventDefault();
  dragDepth = 0;
  $('dropZone').classList.add('hidden');
  addFiles(event.dataTransfer.files);
});
for (const type of ['dragover', 'drop']) {
  document.addEventListener(type, (event) => {
    if (!draggingFiles(event)) return;
    event.preventDefault();
    if (type === 'dragover') event.dataTransfer.dropEffect = canAttach() ? 'copy' : 'none';
  });
}

$('introForm').addEventListener('submit', (event) => void submitIntro(event));
$('composer').addEventListener('submit', (event) => void send(event));
$('input').addEventListener('input', () => {
  autoGrow();
  sendTyping();
});
/**
 * Enter sends — on a device with a real keyboard. On a phone the on-screen keyboard's return key is
 * the only way to write a second line, so there it does what it says and the send button sends.
 */
const typesWithAKeyboard = () => {
  try {
    return !matchMedia('(pointer: coarse)').matches;
  } catch {
    return true;
  }
};

$('input').addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey && typesWithAKeyboard()) {
    event.preventDefault();
    void send();
  }
});
addEventListener('resize', autoGrow);
$('chatEnd').addEventListener('click', () => {
  if (preview) return void endChat();
  $('endConfirm').classList.remove('hidden');
  $('composer').classList.add('hidden');
  $('endYes').focus();
});
$('endKeep').addEventListener('click', () => {
  closeConfirm();
  renderHeader();
  $('input').focus({ preventScroll: true });
});
$('endYes').addEventListener('click', () => void endChat());
$('embedClose').addEventListener('click', () => toHost({ type: 'cnct:close' }));

$('forgetMe').addEventListener('click', () => {
  forget();
  location.reload();
});
document.addEventListener('visibilitychange', () => {
  if (document.hidden || preview || !state.token) return;
  if (liveConversation() && state.socket?.readyState !== WebSocket.OPEN) connectSocket();
  else void resync();
});

/* ── Preview ──────────────────────────────────────────────────────────── */

/**
 * What the builder shows on each screen: a conversation that has an assistant, a hand-over and a
 * person in it, and a finished one waiting for its faces. Made up, and labelled so in the builder;
 * the only real thing on these screens is the client's own configuration.
 */
const minutesAgo = (minutes) => new Date(Date.now() - minutes * 60_000).toISOString();
const PREVIEW = {
  team: [
    { kind: 'person', name: 'Sara', avatar: 'fox-clay' },
    { kind: 'person', name: 'Omar', avatar: 'bird-sea' },
    { kind: 'person', name: 'Huda', avatar: null },
  ],
  assistant: { kind: 'ai', name: 'Alia', mascot: 'drop', colour: 'teal' },
};
PREVIEW.live = {
  id: 'preview-live',
  status: 'OPEN',
  startedAt: minutesAgo(9),
  closedAt: null,
  withPerson: true,
  assignee: PREVIEW.team[0],
  rating: null,
  ratable: false,
};
PREVIEW.liveMessages = [
  { id: 'p1', speaker: 'SYSTEM', body: 'Hi — how can we help?', createdAt: minutesAgo(9), sender: null },
  { id: 'p2', speaker: 'CALLER', body: 'Can I move my appointment to Sunday?', createdAt: minutesAgo(8), sender: null },
  { id: 'p3', speaker: 'AI', body: 'Of course. Which time on Sunday works for you?', createdAt: minutesAgo(8), sender: PREVIEW.assistant },
  { id: 'p4', speaker: 'CALLER', body: 'Morning, if there is anything.', createdAt: minutesAgo(7), sender: null },
  { id: 'p5', speaker: 'OPERATOR', body: 'Done — Sunday at 10:00. See you then!', createdAt: minutesAgo(2), sender: PREVIEW.team[0] },
];
/** The made-up conversation in the visitor's language, so an Arabic preview reads as one. */
const PREVIEW_LINES = {
  en: {
    p2: 'Can I move my appointment to Sunday?',
    p3: 'Of course. Which time on Sunday works for you?',
    p4: 'Morning, if there is anything.',
    p5: 'Done — Sunday at 10:00. See you then!',
    po: 'Your order is on its way.',
  },
  ar: {
    p2: 'هل يمكنني نقل موعدي إلى يوم الأحد؟',
    p3: 'بالتأكيد. أي وقت يناسبك يوم الأحد؟',
    p4: 'في الصباح إن أمكن.',
    p5: 'تم، الأحد الساعة 10:00. نراك قريباً!',
    po: 'طلبك في الطريق إليك.',
  },
};
function localisePreview() {
  const lines = PREVIEW_LINES[lang] ?? PREVIEW_LINES.en;
  for (const message of PREVIEW.liveMessages) {
    if (lines[message.id]) message.body = lines[message.id];
  }
  PREVIEW.list[1].preview.body = lines.po;
}
PREVIEW.closed = {
  ...PREVIEW.live,
  id: 'preview-closed',
  status: 'CLOSED',
  closedAt: minutesAgo(1),
  rating: null,
  ratable: true,
};
PREVIEW.list = [
  { ...PREVIEW.live, lastMessageAt: minutesAgo(2), preview: PREVIEW.liveMessages[4] },
  {
    id: 'preview-old',
    status: 'CLOSED',
    startedAt: minutesAgo(60 * 24 * 6),
    closedAt: minutesAgo(60 * 24 * 6),
    withPerson: false,
    assignee: null,
    rating: { score: 5, comment: null },
    ratable: false,
    lastMessageAt: minutesAgo(60 * 24 * 6),
    preview: { id: 'po', speaker: 'AI', body: 'Your order is on its way.', createdAt: minutesAgo(60 * 24 * 6), sender: PREVIEW.assistant },
  },
];

function startPreview() {
  document.documentElement.classList.add('embed', 'preview');
  state.business = params.get('business') || 'Your business';
  state.greeting = 'Hi — how can we help?';
  state.team = PREVIEW.team;
  state.conversations = PREVIEW.list;
  state.visitor = { displayName: 'Layla', anonymous: false };
  state.seen = { 'preview-old': new Date().toISOString() };
  // An example of the line Home draws from real hours and real reply times.
  state.availability = { open: true, opensAt: null, assistant: false, replyMinutes: 4 };
  document.title = 'Widget preview';
  $('introBusiness').textContent = state.business;
  $('introGreeting').textContent = state.greeting;
  applyWidget(DEFAULT_WIDGET);
  goTab('home');

  const screens = {
    home: () => goTab('home'),
    messages: () => goTab('messages'),
    intro: () => show('intro'),
    chat: () => {
      state.visitor = { displayName: 'Layla', anonymous: false };
      state.current = { conversation: PREVIEW.live, messages: PREVIEW.liveMessages };
      state.justNamed = '';
      show('chat');
      renderChat();
    },
    // A visitor who typed first, being asked who they are.
    details: () => {
      state.visitor = { displayName: 'Visitor 7KQ2', anonymous: true };
      state.justNamed = '';
      state.current = {
        conversation: { ...PREVIEW.live, assignee: null, withPerson: false },
        messages: PREVIEW.liveMessages.slice(0, 3),
      };
      show('chat');
      renderChat();
    },
    ended: () => {
      state.visitor = { displayName: 'Layla', anonymous: false };
      state.ratingStage = {};
      state.current = { conversation: { ...PREVIEW.closed, rating: null }, messages: PREVIEW.liveMessages };
      show('chat');
      renderChat();
    },
  };

  window.addEventListener('message', (event) => {
    const data = event.data;
    if (!data || data.type !== 'cnct:preview') return;
    if (data.business) {
      state.business = data.business;
      $('introBusiness').textContent = data.business;
    }
    if (data.greeting !== undefined) {
      state.greeting = data.greeting;
      $('introGreeting').textContent = data.greeting;
      PREVIEW.liveMessages[0].body = data.greeting || PREVIEW.liveMessages[0].body;
    }
    if (data.requirePhone !== undefined) {
      state.requirePhone = Boolean(data.requirePhone);
      $('introPhoneField').classList.toggle('hidden', !data.requirePhone);
    }
    if (data.config) {
      applyWidget(mergeWidget(data.config));
      localisePreview();
      if (state.screen === 'chat') renderChat();
      if (state.screen === 'home' || state.screen === 'messages') goTab(state.screen);
    }
    if (data.screen && screens[data.screen]) screens[data.screen]();
  });

  if (window.parent !== window) window.parent.postMessage({ type: 'cnct:preview-ready' }, '*');
}

if (preview) startPreview();
// Booted or not, say so: the launcher holds the host's calls until it hears this.
else void boot().finally(() => toHost({ type: 'cnct:ready' }));
