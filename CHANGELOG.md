# CAS changelog

Write every entry in ASD-STE100 Simplified Technical English (STE):

- Use only the approved words of the STE dictionary, with their approved meanings. You can also use technical names: CAS, Stremio, add-on, subtitle, and the names that CAS shows on the screen.
- Write one idea in each sentence. Write 20 words or fewer in an instruction and 25 words or fewer in a description.
- Use the active voice. Use the simple present tense, or the simple past tense for "Before, …".
- Do not use words that end in "-ing", except in technical names.
- Do not use contractions. Write "does not", not "doesn't". Text that CAS shows on the screen stays as it is.
- Write instructions as commands: "Select **Subtitles**", not "**Subtitles** can be selected".
- Use one word for one thing. Use the names that CAS shows on the screen.
- Put the 3 most interesting changes first.

Put changes that are not released under `## Unreleased`. Before each release, read all of `## Unreleased` again and rewrite each entry that does not obey these rules. Then rename that heading to the version number.

## Unreleased

## 1.0.0

- **CAS identity.** New wordmark, TV banner, launcher icon and welcome screens.
- **A quieter interface.** Charcoal surfaces, Inter headings, white focus controls and more space between cards.
- **Simpler navigation.** CAS at left, destinations in the centre and profile at right.
- **Separate installation.** CAS has its own package identity and checks this fork for updates.
- **Subtitles in your language.** Select **Settings → Subtitles → Subtitle language**. Then select a language. Each profile keeps its own language. Subtitles in that language start with the video. They also start when the audio is in that language.
- **All subtitle languages in the player.** **Audio & subtitles** shows all the languages from your subtitle add-ons. Your subtitle language and English are at the top. Before, CAS showed only the language of the TV and English.
- **All audio languages.** **Settings → Picture & Sound → Audio language** has all the languages. Before, it had only 7. Frequently used languages are at the top.
- **CAS keeps your choices from the player.** Select an audio language or subtitles in **Audio & subtitles**. The next episode starts with your choice. When you start the title again, it also starts with your choice. Each profile keeps its own choices.
- When CAS changes to a different source, your subtitle choice stays.
- **See your subtitles quickly.** When the player controls show, a line under the progress bar shows the audio language and the subtitles. For example: "English · Spanish subtitles".
- In **Audio & subtitles**, each heading shows the choice in use. For example: "Subtitles · Spanish". A dot shows the choice in use in each list.
- CAS matches language names from all sources, for example "es", "spa" and "Spanish". Before, some subtitles and audio tracks did not match.
- **Portuguese (Brazil)** and **Portuguese** are 2 different languages.
- An add-on subtitle in your language does not start the video again.
- Lists in **Settings** are not transparent.
- **Skip forward in more movies.** CAS finds the correct position in files that have no index, for example .ts files. Before, a skip forward on the progress bar started these movies again from the beginning.
- Some sources cannot skip. In these sources, CAS shows a message, and the movie continues. To get a different source, select **Change stream**.
- **Title details with all add-ons.** CAS gets title details from your add-ons in their order, as Stremio does. When an add-on gives no details, CAS uses the next add-on. Before, some users saw "Couldn't load this title's details" on all titles.
- CAS shows a title when an add-on sends an episode that has no id.
- **Sources in your audio language.** Select a language above the source list. CAS shows only the sources in that language. Sources with "Multi" or "Dual" audio show for all languages.
- When **Dubbed releases** is **My language first**, the source list opens on your audio language.
- **Profiles stay after a sign-out.** When Stremio signs a profile out of this TV, CAS keeps the profile. Select the profile and sign in again. The settings and the PIN of the profile stay. Before, CAS removed the profile.
- When you remove a profile, CAS does not sign out a Stremio account that another profile uses.
- CAS tells you when you add a Stremio account that another profile already uses.
- The sign-in page tells you how to connect 2 accounts with 1 phone.
- **Add-ons with an http:// address work.** CAS connects to add-ons and sources with an http:// address, for example add-ons on your home network. Before, all of these failed.
- **CAS tells you why there is no source.** For example, an add-on did not answer, or an add-on found only torrents.
- CAS sends its name and version to add-ons. Before, some add-ons blocked CAS.
- Sources with only Dolby Vision show on all TVs. When your TV cannot show them correctly, they are at the end of the list.
- **What is new after each update.** After CAS updates, Home shows the changes in the new version. Select **See all changes** to read all of them. Select **Settings → About → What's new** to read them again.
- **Updates on Fire TV.** CAS does not stop when it asks for permission to install an update. When the TV has no page for this permission, CAS shows the steps.
- After you let CAS install apps, the update continues. Before, you had to select **Install** again.
- When the download of an update stops or is not complete, CAS tells you. Before, nothing happened.
- Before an update, CAS tells you that it closes. Open CAS again after the update.
- **Remove titles from Continue Watching.** Hold OK on a title in **Continue**. Then select **Remove from Continue Watching**. Stremio also removes it on your other devices. When you watch the title again, it comes back.
- **Put back removed titles.** Select **Settings → Profile → Hidden from Continue Watching**. Then select a title. The title goes back to **Continue** at the position where you stopped. The list shows only titles that you removed on this TV.
- Hold OK on a title in **Continue** now opens a menu. To see all sources, select **Choose a source**.
- **See the debrid service of each source.** The source list shows the service of each source, for example **RD** for Real-Debrid. **⚡** shows that the file is ready and starts immediately.
- The player shows the debrid service of the source that plays, for example "Torrentio · 2160p · Real-Debrid".
- **All catalogs from AIOMetadata show.** Before, CAS did not show a catalog that needs a genre, for example an AIOMetadata catalog that is not on the Stremio home page. Now CAS shows these catalogs, as Stremio does. This also corrects a **Shows** page with no rows.

Earlier versions below are the upstream history CAS was built from, kept for reference.

## 1.1

- Each profile keeps its own streaming add-ons. Add-ons that you add or remove in Stremio show in CAS on the next page.
- Continue Watching shows correctly when Home opens for the first time.
- The **Audio & subtitles** panel in the player opens correctly.
- In **Search**, the selected poster is no longer cut off at the top.

## 1.0

- **Press Play and it plays.** CAS picks a good source and starts it. Hold OK on **Play** to see every source.
- **Automatic fallback.** When a stream fails, CAS tries the next one from the same second.
- **Only what your TV can play.** CAS checks your TV for 4K, HDR, Dolby Vision, HEVC, AV1 and DTS. It hides files that the TV cannot play.
- **Profiles.** Up to 5 profiles, each with its own Stremio account. A PIN is optional.
- **Stream preferences in plain words:** **Best picture**, **Balanced** or **Fast start**.
- **Series.** The next episode keeps the same release and quality. **Skip intro** is built in.
- **Continue Watching on the Google TV home screen.**
- **Frame-rate matching.** The TV changes to the film's frame rate.
- **Sync with Stremio.** Watch progress matches your phone and computer.
- **Updates.** CAS finds new versions on GitHub and installs them.
