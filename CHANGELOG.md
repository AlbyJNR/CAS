# Changelog

Write every entry in ASD-STE100 Simplified Technical English (STE):

- Use only the approved words of the STE dictionary, with their approved meanings. You can also use technical names: Alder, Stremio, add-on, subtitle, and the names that Alder shows on the screen.
- Write one idea in each sentence. Write 20 words or fewer in an instruction and 25 words or fewer in a description.
- Use the active voice. Use the simple present tense, or the simple past tense for "Before, …".
- Do not use words that end in "-ing", except in technical names.
- Do not use contractions. Write "does not", not "doesn't". Text that Alder shows on the screen stays as it is.
- Write instructions as commands: "Select **Subtitles**", not "**Subtitles** can be selected".
- Use one word for one thing. Use the names that Alder shows on the screen.
- Put the 3 most interesting changes first.

Put changes that are not released under `## Unreleased`. Before each release, read all of `## Unreleased` again and rewrite each entry that does not obey these rules. Then rename that heading to the version number.

## Unreleased

- **Subtitles in your language.** Select **Settings → Subtitles → Subtitle language**. Then select a language. Each profile keeps its own language. Subtitles in that language start with the video. They also start when the audio is in that language.
- **All subtitle languages in the player.** **Audio & subtitles** shows all the languages from your subtitle add-ons. Your subtitle language and English are at the top. Before, Alder showed only the language of the TV and English.
- **All audio languages.** **Settings → Picture & Sound → Audio language** has all the languages. Before, it had only 7. Frequently used languages are at the top.
- **Alder keeps your choices from the player.** Select an audio language or subtitles in **Audio & subtitles**. The next episode starts with your choice. When you start the title again, it also starts with your choice. Each profile keeps its own choices.
- When Alder changes to a different source, your subtitle choice stays.
- Alder matches language names from all sources, for example "es", "spa" and "Spanish". Before, some subtitles and audio tracks did not match.
- **Portuguese (Brazil)** and **Portuguese** are 2 different languages.
- An add-on subtitle in your language does not start the video again.
- Lists in **Settings** are not transparent.
- **Skip forward in more movies.** Alder finds the correct position in files that have no index, for example .ts files. Before, a skip forward on the progress bar started these movies again from the beginning.
- Some sources cannot skip. In these sources, Alder shows a message, and the movie continues. To get a different source, select **Change stream**.
- **Title details with all add-ons.** Alder gets title details from your add-ons in their order, as Stremio does. When an add-on gives no details, Alder uses the next add-on. Before, some users saw "Couldn't load this title's details" on all titles.
- Alder shows a title when an add-on sends an episode that has no id.

## 1.1

- Each profile keeps its own streaming add-ons. Add-ons that you add or remove in Stremio show in Alder on the next page.
- Continue Watching shows correctly when Home opens for the first time.
- The **Audio & subtitles** panel in the player opens correctly.
- In **Search**, the selected poster is no longer cut off at the top.

## 1.0

- **Press Play and it plays.** Alder picks a good source and starts it. Hold OK on **Play** to see every source.
- **Automatic fallback.** When a stream fails, Alder tries the next one from the same second.
- **Only what your TV can play.** Alder checks your TV for 4K, HDR, Dolby Vision, HEVC, AV1 and DTS. It hides files that the TV cannot play.
- **Profiles.** Up to 5 profiles, each with its own Stremio account. A PIN is optional.
- **Stream preferences in plain words:** **Best picture**, **Balanced** or **Fast start**.
- **Series.** The next episode keeps the same release and quality. **Skip intro** is built in.
- **Continue Watching on the Google TV home screen.**
- **Frame-rate matching.** The TV changes to the film's frame rate.
- **Sync with Stremio.** Watch progress matches your phone and computer.
- **Updates.** Alder finds new versions on GitHub and installs them.
