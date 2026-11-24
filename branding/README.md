# jkit branding

The **icon** is a periwinkle j whose dot is a teal layer: jkit keeps many JDKs installed and makes one of them
active, the one on top. It is drawn the way the family's icons are drawn (Editora, Termina, Torrenta, Mirada,
Pauta): the Ink navy tile, the same corner radius and teal gradient, two shapes so it reads at 32 px.

The **wordmark** is "jkit" in Inter ExtraBold with the same teal layer in place of the j's dot. The icon and the
wordmark are separate marks: use the icon where there is only a square (favicon, avatar, app tile), the wordmark
where the name is set in text (README, website header, social card).

| File | What it is |
|---|---|
| `jkit-icon.svg` | The icon, 512 × 512. Copied to the website as `public/favicon.svg`. |
| `jkit-icon.png`, `jkit-icon-{256,128,64,32,16}.png` | Rendered sizes of the icon. |
| `jkit-wordmark.svg`, `jkit-wordmark-dark.svg` | The wordmark with the letters outlined (no font needed), for light and dark backgrounds. |
| `jkit-wordmark.png`, `jkit-wordmark-dark.png` | The wordmarks rendered 192 px high. |
| `social-card.html` → `social-preview.png` | The 1280 × 640 card for shared links (the website's `og.png`) and the GitHub repository's social preview. |

Colors and type are the family's own: Ink `#1B1E2A`, periwinkle `#9AA2D4`, teal `#5EEAD4`→`#22B8A6`, light ink
`#E8EAF4` on dark; Inter and JetBrains Mono. jkit adds none.

Re-render after changing a mark (needs [sharp](https://sharp.pixelplumbing.com) and Chrome; the card loads its
fonts from Google Fonts):

```bash
cd branding
node -e 'const s=require("sharp");(async()=>{for(const n of [512,256,128,64,32,16])await s("jkit-icon.svg",{density:Math.max(72,n/512*288)}).resize(n,n).png().toFile(n===512?"jkit-icon.png":`jkit-icon-${n}.png`);for(const f of ["jkit-wordmark","jkit-wordmark-dark"])await s(f+".svg",{density:300}).resize({height:192}).png().toFile(f+".png")})()'
google-chrome --headless=new --hide-scrollbars --virtual-time-budget=5000 --window-size=1280,640 --screenshot="$PWD/social-preview.png" "file://$PWD/social-card.html"
```
