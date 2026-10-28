# Third-party notices

nesturbator is MIT-licensed (see [LICENSE](LICENSE)). Two things in this
repository come from elsewhere: the vendored `libretro/libretro.h`, and the
CPU test data under `tests/vectors/`. Holy Mapperel's test ROMs under
`tests/roms/hm/` and the nes-runner game ROM `tests/roms/runner.nes` are listed
below too.

## libretro.h

- File: `libretro/libretro.h`, unmodified
- Upstream: https://github.com/libretro/RetroArch, `libretro-common/include/libretro.h`
- Commit: 69a4f0ea1e8aaf442ae4858f2e7f2b31a1776576 (tag v1.22.2)
- SHA-256: bd3398d29c3763d18617087020ff56b5450a48a63123403a4b674f8e79947acb

Its licence, as stated in the file:

```
The following license statement only applies to this libretro API header (libretro.h).

Copyright (C) 2010-2024 The RetroArch team

Permission is hereby granted, free of charge,
to any person obtaining a copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## SingleStepTests 65x02

CPU test vectors (test data, not code), converted to N65V by `tools/vecconv`
or cut as byte-range prefixes of the upstream JSON.

- Upstream: https://github.com/SingleStepTests/65x02, `nes6502/v1/`
- Commit: 2f6980a2d95757486c7bee24355c360e40e2a224

| File | What it is | SHA-256 |
|---|---|---|
| `tests/vectors/65x02-sample.n65v` | the first 100 tests of each of the 256 files, as N65V | 0c318cec0bd4031396964ca9a0de0daf25c2651c28f34a0d0616a9294430e109 |
| `tests/vectors/fixtures/a9-first3.json` | a byte-range prefix of one file, see `tests/vectors/fixtures/README.md` | 3195bc1b6d17fb002f8d0a4f8f5b99077d42d62f79e35161d9de1f7d4bf1d83b |
| `tests/vectors/fixtures/02-first3.json` | a byte-range prefix of one file, see `tests/vectors/fixtures/README.md` | 2738dad0c19bc0e4dc4d3a131a7cd3768a1493df9ec665b0e738fa397fbbb58f |
| `tests/vectors/fixtures/a9-tail.json` | a byte-range prefix of one file, see `tests/vectors/fixtures/README.md` | 6d60b42a8fca08c75e7e3ea364085be67ea786c9d15e279e6fe9e4790ab42ddc |

Its licence, the upstream `LICENSE` at that commit:

```
MIT License

Copyright (c) 2024 Thomas Harte et al

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Holy Mapperel

Test ROMs for the mapper 1, 2, 3, 4 and 7 boards, unmodified, under `tests/roms/hm/`.

- Upstream: https://github.com/pinobatch/holy-mapperel (c) Damian Yerrick
- Release: v0.02, asset `holy-mapperel-bin-0.02.7z`, 17964 bytes
- Asset SHA-256: 70f85671e21f293599baebb662faeb06a4c04e9c9ceb283d96d4197f09e4ce7a
- Commit: c022622274ca8b83d214dea97e4388a6b0e92d8a
- Files, each byte-identical to the one in the asset:
  - `M2_P128K_CR8K_V.nes` SHA-256 c7e83755bd9adbb7c705ea9f29535442af7390444632f9f824fcf4c00632069b
  - `M3_P32K_C32K_H.nes` SHA-256 499891c6d8c7a1e7631bdc601d9d624938842735f92fe1fda7b19ef9fae514b7
  - `M7_P128K_CR8K.nes` SHA-256 4aa0050f36ae17e17701506821e5147df5b843bcdf2827468fa9c66e4b7ac1ba
  - `M1_P512K_CR8K_S32K.nes` SHA-256 7db5b5191ce842d44a5a8cb132a58d7116741053d4bd0f8a5def832be80e655a
  - `M1_P128K_CR8K.nes` SHA-256 0595f0896d71d6e54ad19b00a2cdfb6d1665e3166167a06935c5971105ed5793
  - `M1_P128K_C32K_W8K.nes` SHA-256 3d330d32bb45d41ce6c9666b80f580ff2912444b44f833ee89d062794abdfe32
  - `M1_P128K_C128K_S8K.nes` SHA-256 2263d212fe9c8e857f89c846962e7e78f3ba885e1413644b94e50cfff65ecd08
  - `M1_P512K_CR8K_S8K.nes` SHA-256 c3f239ea8f2fa1023a272b5e1b5add884b583e79ffd6487771cc53a1f4566b5c
  - `M4_P128K_CR8K.nes` SHA-256 edb301630dae50a8470c1fb914f436434a150c886d9537d072275b751a9b4125
  - `M4_P256K_C256K.nes` SHA-256 278041824af3a0530d6a1367fa1a1d3bb544d2dd1e97ad8372673f7fe0379b17

The test decoder in `tests/holymapperel/` carries the ROM's 8x8 font as a
table (tiles $30-$39 and $01-$06); the font is Holy Mapperel's.

Its licence, from the LICENSE file at that commit:

```
The zlib License
================

Copyright (c) 2017 Damian Yerrick

This software is provided 'as-is', without any express or implied warranty. In
no event will the authors be held liable for any damages arising from the use of
this software.

Permission is granted to anyone to use this software for any purpose, including
commercial applications, and to alter it and redistribute it freely, subject to
the following restrictions:

1.  The origin of this software must not be misrepresented; you must not claim
    that you wrote the original software. If you use this software in a product,
    an acknowledgment in the product documentation would be appreciated but is
    not required.

2.  Altered source versions must be plainly marked as such, and must not be
    misrepresented as being the original software.

3.  This notice may not be removed or altered from any source distribution.
```

## nes-runner

A small NES game, used as the boot-to-play test ROM: `tests/roms/runner.nes`, unmodified.

- Upstream: https://github.com/zorchenhimer/nes-runner
- Release: v1, asset `runner.nes`, 81936 bytes
- Commit: 47537be0ccb8baeb8462547a1c08888ee2a6cfac (47537be)
- SHA-256: 1a67bfabdde2d96eacb04eb7f9d17e8da6c082d4976c318315fe31e2d19b4384

The game's credits screen shows contributor handles as game text. That text is
part of the MIT-licensed game and is not repeated here.

The game embeds the FamiTracker NSF music driver, version 2.11, by jsr, as its
source file `sound/driver.s` states in its header comment (lines 2 to 4).

Provenance of the licence: the upstream `LICENSE.md` arrived in commit
e2d70cfcc64b947a26e7c05d3db55c240686169b (e2d70cf, 2019-06-28), three days after
the v1 tag, so the v1 tag itself carries no licence file. GitHub reports the
repository as MIT. The text below is that `LICENSE.md`:

```
Copyright 2019 Zorchenhimer

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
