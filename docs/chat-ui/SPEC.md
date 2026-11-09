# CNCT 1.0 — chat UI, attachments, Arabic, and the full visitor API

One spec, shared word for word by `cnct-web-sdk`, `cnct-ios-sdk` and `cnct-android-sdk`. The three
SDKs ship **1.0.0 together**, with the same features, the same option names and the same words in
English and Arabic. Where this spec says "every platform", it means all three; where a platform
needs its own idiom, it says so.

What 1.0 adds to the headless 0.x SDKs:

1. **A ready-made chat interface** that a business can brand: colours, font, corner radius, logo,
   light/dark, title, and its own wording.
2. **Arabic and right-to-left**, with every word of the interface in both languages.
3. **Attachments both ways**: customers send photos and files, and see what the team sends back.
4. **The rest of the visitor API** the hosted widget already uses, so the SDKs stop lagging it.
5. **A production release**: versioned, documented, tested, with screenshots as proof.

The headless client stays the foundation. The UI is built **only** on the public headless API: if
the UI needs something the headless client does not expose, add it to the headless client first.

---

## The reference: the hosted widget

CNCT's own chat page already does everything in this spec, in production. Its source is served,
unminified and commented, at:

- `https://app.doo.ooo/chat-assets/chat.js` (3,452 lines: every endpoint, frame, string and rule)
- `https://app.doo.ooo/chat-assets/chat.css`
- the page itself at `https://app.doo.ooo/chat/<publicKey>`

If your environment cannot reach `app.doo.ooo`, the same three files are on this repo's branch
`reference/hosted-widget` (`git fetch origin reference/hosted-widget`). **That branch is reference
material. Never merge it, never copy it into the SDK.** Read it; port behaviour, not code.

Useful places in `chat.js`:

| What                                           | Where (approx. line)  |
| ---------------------------------------------- | --------------------- |
| The English and Arabic string table            | 226–489               |
| Language choice, `dir`, Latin digits in Arabic | 491–520, 808–830      |
| Boot, identify, session start                  | 959, 1016, 1362       |
| Past conversations, open one, mark read        | 1105, 1255, 1152      |
| A message bubble, `dir="auto"`, links          | 1170–1200, 1940–1975  |
| Flow choices (buttons / list), suggestions     | 1620–1730, 1850–1870  |
| Attachments: fetch, show, upload (XHR + progress) | 2000–2030, 2420–2460 |
| Profile, rating, send, socket, typing, close   | 2135, 2240, 2315, 2567, 2735, 2753 |

---

## Part 1 — the headless additions

Every call carries the visitor token in `x-chat-token`, never in `Authorization` and never in a
URL. Responses are allowlisted by the server; decode leniently (ignore unknown fields).

### The inbox (`boot()`)

`GET /api/chat/public/:publicKey` also returns `widget`, `team` and `availability`. Add all three
to the inbox model. The UI reads these fields of `widget`:

| Field                         | Values                          | The UI uses it for                       |
| ----------------------------- | ------------------------------- | ---------------------------------------- |
| `brand.color`                 | `#rrggbb`                       | default primary colour                   |
| `brand.appearance`            | `light` / `dark` / `auto`       | default appearance (`auto` = system)     |
| `brand.businessName`          | string or null                  | (already collapsed into `business`)      |
| `brand.avatarUrl`             | URL or null                     | default logo; null = first letter        |
| `brand.language`              | `auto` / `en` / `ar`            | default language                         |
| `brand.showBranding`          | bool                            | default "Powered by CNCT" line           |
| `chat.allowAttachments`       | bool                            | show the attach button                   |
| `chat.allowEmoji`             | bool                            | (web only: the emoji panel, optional)    |
| `chat.allowEndConversation`   | bool                            | show "End conversation"                  |
| `chat.start`                  | `details` / `message`           | ask name first, or let them type first   |
| `chat.askForRating`           | bool                            | rating card when a conversation closes   |

`availability` says whether the team is in, when they are back, and whether the assistant answers
now. Port the sentence the widget builds from it.

### Session

- `POST /api/chat/public/:publicKey/session` takes `{ displayName?, phone?, timezone? }`. Send the
  device's IANA time zone as `timezone`.
- `GET /api/chat/public/session?timezone=…` resumes.

### Messages

`POST /api/chat/public/messages` and the socket `send` frame take the same body:

```json
{ "body": "≤ 4000 chars", "clientKey": "≤ 64", "attachmentIds": ["≤ 10 ids"], "replyId": "≤ 200" }
```

Words, files, or both, never neither (the server refuses an empty body with no files). `replyId`
is the `id` of a flow choice the visitor tapped; send the choice's `title` as `body`.

A message as the visitor sees it carries, beside `id`, `speaker`, `body`, `createdAt`, `clientKey`:

- `sender`: who wrote it, for the face and first name (`null` for the visitor and system lines).
  An operator is `{ kind: "person", name, avatar }`; the assistant has its own shape.
- `type` and `data`: the content type (`TEXT` unless it is something richer) and its allowlisted
  fields.
- `flow` (assistant messages only): `{ text, layout: "buttons" | "list", choices: [{ id, title,
  description? }], footer?, listButton?, media?: { kind, url, filename? }, expects? }`.
  `expects` is `name` / `number` / `email` / `phone` / `date`: pick that keyboard.
- `suggestions` (assistant only): up to three short replies to offer as chips.
- `replyTo`: `{ messageId, mine, excerpt, attachmentKind? }`, a quoted earlier message.
- `attachments`: `[{ id, kind, mimeType, filename, bytes }]`, only files that are stored.

Model all of these. Unknown `type` values render as their `body`.

### Attachments

**Sending** is two steps, as the widget does:

1. Upload each file on its own: `POST /api/chat/public/attachments`, `multipart/form-data`, one
   part named **`file`**. Answer `201 { attachment: { id, kind, mimeType, filename, bytes } }`.
   Report upload progress (0–1) per file.
2. Send the message with those ids in `attachmentIds`.

Limits (the server enforces them; check first so the UI can say so before uploading):

- up to **10** files per message, **50 MB** each;
- accepted types: images `image/jpeg`, `image/png`, `image/webp`, `image/gif`; audio `audio/aac`,
  `audio/mpeg`, `audio/ogg`, `audio/mp4`, `audio/amr`; video `video/mp4`, `video/3gpp`,
  `video/quicktime`; documents `application/pdf`, `application/msword`, `.docx`
  (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`), `.xlsx`
  (`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`), `text/plain`, `text/csv`.
- **HEIC/HEIF is not accepted.** iPhones (and some Android phones) take photos in HEIC: convert to
  JPEG before upload. Downscale photos to at most 2560 px on the long side (JPEG quality ~0.85);
  never upscale. Leave other files untouched.

Refusals: `403` the inbox takes no files; `409` the conversation has ended; `413`/`422` too big or
the wrong type; `503` the deployment keeps no files. The body's `error` is a sentence for a person;
map the status to the usual `CnctErrorCode`s.

**Receiving**: `GET /api/chat/public/attachments/:id` with the token header returns the bytes (any
conversation of this visitor's, so past chats work). Cache by id.

### Signed-in users

`POST /api/chat/public/:publicKey/identify` with
`{ userId, userHash, name?, email?, phone? }` answers `{ token, visitor }`. `userHash` is
`HMAC-SHA256(userId, the inbox's identity secret)` in lowercase hex, **computed on the business's
own server**. The secret never goes in an app or a page; say so in the docs, with a 10-line server
example. Send the current `x-chat-token` with the call so an anonymous chat started before sign-in
moves across. Store the returned token in place of the old one. `409` means identity is not set up
for this inbox; `401` means the hash did not verify. In the UI, a failed identify is logged for the
developer and the customer still gets a working anonymous chat, as the widget does.

### The rest

| Call                                                   | Answers                       |
| ------------------------------------------------------ | ----------------------------- |
| `GET /api/chat/public/conversations`                   | up to 50, newest first, each with its last message |
| `GET /api/chat/public/conversations/:id`               | one, with its messages        |
| `POST /api/chat/public/conversations/:id/read`         | `204`: the visitor has the team's replies on screen |
| `POST /api/chat/public/conversations/:id/rating`       | `{ conversation }`; `409` once it can no longer be rated |
| `POST /api/chat/public/profile` `{ displayName, phone? }` | `{ visitor }`              |

Read the request shapes for rating and read from `chat.js` (2240, 1152); port them exactly.

### Socket frames to add

Besides `chat.message`, `chat.typing`, `chat.conversation.updated` and `chat.closed`:

- `chat.message.updated`: a message changed (edited, deleted, a file finished storing). Replace it
  in place by `id`.
- `chat.read`: the team has read the visitor's messages up to a point. Expose it so a UI can show
  "Seen".

Read their payloads in `chat.js`. Unknown frames stay ignored.

---

## Part 2 — the chat UI

### Where it lives

| Platform | Package                                                     | Entry points |
| -------- | ----------------------------------------------------------- | ------------ |
| Web      | subpath export `cnct-web-sdk/ui` (no framework dependency)  | `<cnct-chat>` custom element (Shadow DOM) via `defineCnctChat()`, and `mountChat(element, options)` returning `{ update(options), destroy() }` |
| iOS      | SPM product and target **`CNCTUI`**, depending on `CNCT`    | SwiftUI `CnctChatView`; UIKit `CnctChatViewController` |
| Android  | module **`:cnct-ui`**, artifact `cnct-ui`, Jetpack Compose  | `@Composable CnctChat(...)`; `CnctChatActivity` for apps without Compose |

The headless artifacts must not gain UI dependencies. Someone who wants only the headless client
installs only that.

Every entry point takes the same options:

| Option        | Type (idiomatic per platform)        | Default |
| ------------- | ------------------------------------ | ------- |
| client        | the headless chat client, or a public key (the UI then makes one) | required |
| `theme`       | `CnctChatTheme`                      | from the server, then built-in |
| `language`    | `auto` / `en` / `ar`                 | server `brand.language`, then the device |
| `strings`     | per-language overrides of any key    | none |
| `identity`    | `{ userId, userHash, name?, email?, phone? }` | none (anonymous) |
| `onClose`     | callback for the header's close button; hidden when absent | none |
| `onEvent`     | callback for analytics: opened, started, sent, attachment sent, ended, rated | none |

### Theming

**Precedence: what the app passes > the inbox's widget settings on the server > built-in
defaults.** So a business can brand the chat from the CNCT console with no code, or pin it in code.

`CnctChatTheme` has the same token names everywhere:

| Token             | Meaning                               | Default |
| ----------------- | ------------------------------------- | ------- |
| `primary`         | brand colour: send button, links, header accent | `brand.color`, else `#b52d93` |
| `onPrimary`       | text/icons on `primary`               | black or white, whichever is readable (port the widget's contrast rule) |
| `background`      | the screen                            | light `#ffffff` / dark `#0f1115` |
| `surface`         | header, composer, cards               | light `#f6f7f9` / dark `#171a21` |
| `text`            | main text                             | light `#111418` / dark `#eef0f3` |
| `textMuted`       | timestamps, hints                     | light `#5b6573` / dark `#9aa3af` |
| `border`          | dividers, outlines                    | light `#e3e6ea` / dark `#2a2f38` |
| `visitorBubble` / `onVisitorBubble` | the customer's own messages | `primary` / `onPrimary` |
| `agentBubble` / `onAgentBubble`     | the team's and assistant's messages | `surface` / `text` |
| `cornerRadius`    | bubbles and cards                     | 18 |
| `font`            | the typeface (web: a CSS font-family; iOS: `Font`/font name; Android: `FontFamily`) | system |
| `logo`            | header image                          | `brand.avatarUrl`, else the business's first letter on `primary` |
| `title`           | header title                          | `business` |
| `appearance`      | `light` / `dark` / `system`           | `brand.appearance` (`auto` = `system`) |
| `showPoweredBy`   | the "Powered by CNCT" line            | `brand.showBranding` |

Light and dark each have their own colours; an app may set either or both. On the web the tokens
are also CSS custom properties on the element (`--cnct-primary`, `--cnct-on-primary`, …, kebab
case), so a stylesheet can theme it without JavaScript.

### Language and right-to-left

- **The words**: `strings.json` in this folder is the widget's own table, 129 keys, English and
  Arabic, identical keys. Ship it as the platform's resources (web: a TS module; iOS: a
  `Localizable.xcstrings` or `.strings` per language; Android: `values/strings.xml` and
  `values-ar/strings.xml`). Keep the keys; on Android, convert camelCase keys to
  `cnct_snake_case` resource names and `{name}` to positional `%1$s` args, and keep a test that the
  two tables agree. A missing Arabic key falls back to English, never to the key itself.
- **Choosing**: `auto` takes the first of the device's preferred languages that is `ar` or `en`,
  else English.
- **Direction**: Arabic mirrors the whole interface: header, composer, bubble sides (the
  visitor's own messages sit on the trailing side in both directions), back/send icons, progress
  bars, swipe directions. Use the platform's layout direction (`dir` on the root, SwiftUI
  `layoutDirection`, Compose `LocalLayoutDirection`), not hand-swapped margins.
- **Each piece of user text takes its own direction** from its first strong character, whatever the
  interface language: message bodies, names, the greeting, choice titles, file names, quotes. (Web
  `dir="auto"`; Android `TextDirection.Content`; iOS: detect the first strong character and set the
  alignment and `writingDirection`.) An Arabic message in an English interface reads right to left,
  and the reverse.
- Phone numbers, emails and URLs stay left-to-right inside Arabic text.
- **Digits**: Latin digits in Arabic (`ar-u-nu-latn`), as Gulf apps write them. Times and dates use
  the chosen language.
- Choosing a language never changes the business's own words (greeting, flow text): those are shown
  as written.

### What the screen does

Port the widget's conversation view, not its home screen or launcher.

- **Opening**: loading state; then, from `boot()`: an invalid key (`linkInvalid`, `linkInvalidBody`),
  an inbox switched off (`notTakingChats`), or the chat. A stored token resumes the conversation.
  `identity`, when given, is applied before anything else.
- **Starting**: `chat.start = details` asks for a name first (and a phone number when
  `requirePhone`); `message` lets them type first and starts the session on the first send.
- **Header**: logo, title, the availability line, "End conversation" when allowed, close when
  `onClose` is given.
- **The thread**: oldest at the top, scrolled to the newest; consecutive messages from one sender
  grouped, with that sender's face and first name on the team's side; system lines centred;
  timestamps; links tappable; quotes (`replyTo`); flow choices as buttons or a list (tapping sends
  the title with `replyId`); assistant `suggestions` as chips; the typing indicator; a pending
  message dimmed, a failed one marked with a retry action; a "you're offline, reconnecting" banner
  while the socket is down.
- **The composer**: grows to a few lines, then scrolls; send disabled when there is nothing to send;
  the keyboard type follows the last flow message's `expects`; the attach button when
  `chat.allowAttachments` is true.
- **Attaching**: photo library (several at once), camera on phones, and files. Picked files show as
  chips above the composer with a thumbnail or icon, name and size, an upload progress ring, and a
  remove button. Refuse the 11th file, a file over 50 MB, or a type not accepted, with a sentence,
  before uploading. Send waits for uploads to finish. Use the system pickers (web `<input
  type=file>` and drag/drop/paste; iOS `PHPickerViewController`, `UIImagePickerController` for the
  camera, `UIDocumentPickerViewController`; Android Photo Picker `PickMultipleVisualMedia`,
  `TakePicture`, `OpenMultipleDocuments`). No storage permissions.
- **Showing files**: images inline as a thumbnail with the right aspect ratio, tap for a full-screen
  viewer (pinch to zoom on phones, swipe/escape to close); everything else as a card (icon by kind,
  file name, size) that downloads with the token and opens in the system viewer or share sheet
  (web: a download with the original file name). Flow `media` uses its public URL.
- **Read**: when a team reply is on screen and the app is in the foreground, post `read` once for
  that reply.
- **Ended**: a closed conversation shows that it ended, the rating card when `askForRating` (port
  the widget's rating question and answers), and "Start a new conversation".
- **Accessibility**: Dynamic Type / font scaling up to the largest sizes without clipping;
  VoiceOver / TalkBack labels in the chosen language on every control and message ("You said…",
  "Sara said…", "Photo, 1.2 MB"); touch targets 44 pt / 48 dp; contrast at least 4.5:1 for text.
- **Phones**: the composer rides above the keyboard; the newest message stays visible when the
  keyboard opens; safe areas respected.

---

## Part 3 — tests and proof

### The fixture conversation

Every platform renders the same scripted conversation through a **fake transport and fake socket**
(no network), for its unit tests and its screenshots:

1. System: the greeting ("Hello. How can we help?" / "مرحبًا. كيف يمكننا مساعدتك؟").
2. Visitor: "Do you open on Fridays?" / "هل تفتحون يوم الجمعة؟"
3. Assistant (face + name "Assistant"): "Yes, from 2pm to 10pm. Would you like to book a visit?" /
   "نعم، من الساعة 2 ظهرًا حتى 10 مساءً. هل تود حجز زيارة؟", with flow buttons "Book a visit" and
   "Talk to a person" / "احجز زيارة" and "تحدث مع موظف".
4. Visitor: a photo (`photo.jpg`, 1.2 MB, landscape).
5. Operator "Sara" / "سارة": "Thanks, here is your invoice." / "شكرًا، هذه فاتورتك." with
   `invoice.pdf` (220 KB).
6. Visitor: a mixed line, "Call me on +973 3311 2233 please" / "اتصلوا بي على +973 3311 2233 من فضلك".
7. Visitor: "Can I pay by card?" / "هل يمكنني الدفع بالبطاقة؟", **failed**, with retry.
8. The team is typing.

Plus a composer with two picked files mid-upload (one image at 40%, one PDF done).

### Screenshots, as CI artifacts

CI renders the fixture as PNGs and uploads them as an artifact named **`screenshots`**:
`{en,ar} × {light,dark}` with the default theme, plus `{en,ar}` with a custom theme
(`primary #D7262E`, `cornerRadius 8`, a non-system font, a logo image). Ten images per platform,
named `chat-<lang>-<appearance>.png` and `chat-<lang>-custom.png`. These are what a reviewer looks
at to approve the release: they must show the real component, not a mock.

- Web: Playwright (Chromium) in CI, screenshotting the component on a test page.
- iOS: in `xcodebuild test` on the simulator, render `CnctChatView` (e.g. `ImageRenderer`, iOS 16+
  simulator) or an XCUITest of the example app in fixture mode; attach the PNGs and upload them.
- Android: Roborazzi or Paparazzi on the JVM.

### Unit tests (at least)

Upload: multipart shape, progress, the size/type/count checks before upload, HEIC→JPEG, the
refusal mapping. Send with `attachmentIds` and with `replyId`. Identify replaces the token. `read`
posted once per reply. `chat.message.updated` replaces in place. Theme precedence (app > server >
default). Language choice from device lists. The string tables agree (same keys, no empty Arabic
value). Contrast rule for `onPrimary`.

---

## Part 4 — the release

- **Version 1.0.0** in every place each repo keeps one (and the test that checks they agree), and a
  `CHANGELOG` entry.
- **README**: install pinned to `v1.0.0`; "Chat UI in 10 lines" first; theming (with the token
  table); Arabic and RTL; attachments (with the limits); signed-in users (with the server-side HMAC
  example in Node, and the warning about the secret); then the headless API as before.
- **The example app** (web `examples/`, iOS `Examples/ChatDemo`, Android `:sample`) uses the UI kit,
  with a switch for language, appearance and the custom theme, and a fixture mode that runs with no
  key.
- **CI** builds and tests every module, including the UI, and uploads the screenshots.
- **Do not tag and do not merge.** Open pull requests into `main`; the release is tagged after a
  review of all three platforms together.
- **Never name a client** in this repo: not in code, docs, commits, PR titles or descriptions. The
  custom theme in tests is "custom", not anybody's brand.
