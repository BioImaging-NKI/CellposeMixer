# CellposeMixer

A [QuPath](https://qupath.github.io) extension for segmenting cells in multiplexed immunofluorescence images (such as CODEX) with Cellpose.

Cellpose takes only two channels: a nucleus channel and a cytoplasm/membrane channel. With dozens of markers you have to choose which ones to combine and how to scale them. CellposeMixer lets you do that interactively: tick the channels for each role, set their intensity ranges, see the combined image live, and run Cellpose on your annotations.

## Installation

Requires QuPath 0.7 and the [BIOP Cellpose extension](https://github.com/BIOP/qupath-extension-cellpose) with a Cellpose 3 environment.

- **Via the NKI catalog (recommended):** in QuPath, open *Extensions → Manage extensions → Manage extension catalogs*, add `https://github.com/BioImaging-NKI/qupath-nki-catalog`, then install CellposeMixer. QuPath will tell you when updates are available.
- **Manually:** download the `.jar` from [Releases](https://github.com/BioImaging-NKI/CellposeMixer/releases) and drag it onto QuPath.

Then open *Extensions → CellposeMixer → Open CellposeMixer…*.
