**English** · [Русский](DESIGN.ru.md)

# Interface design

Focus and Studio are two views of one dialog. Focus gives most of the space to the conversation; Studio adds the inspector of changes, helpers and the run. Switching the view must not cancel work or lose a draft.

Visual tokens and components live in `design-system`; the app UI lives in `app-desktop`. When changing the interface, use the existing components instead of copying styles.

- Neutral surfaces, graphite primary actions, a restrained green accent.
- The Medeo font: Sans for the interface, Mono for code, paths and commands (`ChillerFonts`); the zero is slashed in both. System fonts are not used in the UI.
- Light and dark themes with the same hierarchy in both.
- A compact composer with a field that grows with its content and reachable mode/model settings.
- Errors, cancellation, loading and stale data are distinguishable. Having a key does not mean the connection was checked.
- Results are built from the journal; the end of a turn does not mean the task was confirmed correct.
- Animations are short and do not block input; attachments are prepared in the background.

Check a narrow and a wide window, long names, tables, code, images, keyboard navigation and focus retention. Offscreen rendering helps check layout but does not replace native checks of the clipboard, IME and accessibility.

README screenshots: `./gradlew :app-desktop:readmeShots`. It is a scripted demo, not proof that an external provider works.
