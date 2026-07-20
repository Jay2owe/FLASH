# Analysis Help Image Assets

This folder contains packaged images used by the per-analysis help dialogs.

## Folder Layout

Use one folder per `AnalysisHelpTopic.key`:

```text
help/analysis/<analysis-key>/setup.png
help/analysis/<analysis-key>/workflow.png
help/analysis/<analysis-key>/example-output.png
```

Topic entries should use classpath resource paths, for example:

```java
new AnalysisHelpTopic.HelpImage(
        "/help/analysis/intensity/setup.png",
        "Setup",
        "Intensity channel and ROI choices before measurement.",
        true);
```

Use `optional=true` while an image is a planned placeholder. Once final content depends on an image, set `optional=false` or use the three-argument constructor so tests fail if the packaged file is missing.

## Image Rules

- Use generated, anonymised, schematic, or deliberately fake example images.
- Do not commit private microscopy data, patient metadata, raw experiment captures, or real file paths.
- UI screenshots may show FLASH dialogs and controls, but path fields must contain generic project names.
- Result examples should explain output shape and meaning, not claim to be measured data.
- Keep PNGs small enough for dialogs to open quickly. Prefer cropped screenshots and compressed PNGs.

## Update Rules

After adding or replacing images, run:

```text
./mvnw.cmd -Dtest=flash.pipeline.help.AnalysisHelpAssetTest test
```

The asset test validates that required images exist in packaged resources and that missing planned images are explicitly marked optional.
