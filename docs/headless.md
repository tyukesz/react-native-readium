# Headless publication index

Use `openPublicationHeadless()` to get Readium publication metadata, table of contents, and stable `positions` without mounting `ReadiumView`.

```ts
import { openPublicationHeadless } from '@tyukesz/react-native-readium'

const { metadata, tableOfContents, positions } = await openPublicationHeadless({
  url: fileUrlOrAbsolutePath,
  id: `index:${bookId}`,
})

console.log(metadata.title)
console.log(tableOfContents.length)
console.log(positions.length)
```

Notes:
- `positions` are stable “virtual pages” from Readium and do not depend on font/layout.
- Computing positions can be expensive; cache results in the app.
- If the publication is DRM-protected (e.g. LCP) and requires a passphrase, the call rejects with `E_DRM_NEEDS_USER_INTERACTION`.
