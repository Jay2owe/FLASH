# Build-time libraries

A small Maven repository (`flash-bundled` in `pom.xml`) for libraries that are not
in a public Maven repository, so FLASH builds on any machine and in GitHub Actions.

| Library | Version | Used for | In the FLASH jar? | Licence and source |
|---|---|---|---|---|
| Figure Panel Builder | 0.4.0 | Representative-figure export | Yes, relocated to `flash.internal.fpb` | BSD 3-Clause; https://github.com/Jay2owe/FigurePanelBuilder, https://doi.org/10.5281/zenodo.24328268 |
| 3D Objects Counter Plus | 0.1.0 | Compiling Enhanced Classical segmentation; Fiji supplies it at run time | No | https://github.com/Jay2owe/3DObjectsCounterPlus |
| mcib3d-core (3D ImageJ Suite) | 4.1.7b | Compiling optional 3D measurements; Fiji supplies it at run time | No | GPL-3.0; https://framagit.org/mcib3d/mcib3d-core |

The jars are the unmodified upstream files.
