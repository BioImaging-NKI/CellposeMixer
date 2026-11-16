# CellposeMixer

A [QuPath](https://qupath.github.io) extension for segmenting cells in multiplexed immunofluorescence images (such as CODEX) with Cellpose.

We segment with Cellpose 3's `tissuenet_cp3` model, trained on the [TissueNet](https://doi.org/10.1038/s41587-021-01094-0) dataset of multiplexed tissue images. Its input is a two-channel image, like the TissueNet images: one nucleus channel and one cytoplasm/membrane channel. With dozens of markers you have to choose which ones to combine and how to scale them. CellposeMixer lets you do that interactively: tick the channels for each role, set their intensity ranges, see the combined image live, and run Cellpose on your annotations.

## Requirements

- QuPath 0.7
- A working **Cellpose 3** installation: a Python environment with Cellpose 3 (Cellpose 4 / Cellpose-SAM uses different models and is not supported)
- The [BIOP Cellpose extension](https://github.com/BIOP/qupath-extension-cellpose), with the path to that Python environment set in *Edit → Preferences → Cellpose/Omnipose*

## Installation

- **Via the NKI catalog (recommended):** in QuPath, open *Extensions → Manage extensions → Manage extension catalogs*, add `https://github.com/BioImaging-NKI/qupath-nki-catalog`, then install CellposeMixer. QuPath will tell you when updates are available.
- **Manually:** download the `.jar` from [Releases](https://github.com/BioImaging-NKI/CellposeMixer/releases) and drag it onto QuPath.

Then open *Extensions → CellposeMixer → Open CellposeMixer…*. Use *Check Cellpose setup* in the same menu to confirm that Cellpose is found.

## References

- Stringer C, Pachitariu M. Cellpose3: one-click image restoration for improved cellular segmentation. *Nat Methods* 22, 592–599 (2025). [doi:10.1038/s41592-025-02595-5](https://doi.org/10.1038/s41592-025-02595-5)
- Greenwald NF, et al. Whole-cell segmentation of tissue images with human-level performance using large-scale data annotation and deep learning. *Nat Biotechnol* 40, 555–565 (2022). [doi:10.1038/s41587-021-01094-0](https://doi.org/10.1038/s41587-021-01094-0)
