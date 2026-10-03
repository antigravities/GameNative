This is an unofficial **fork** of GameNative that I put together to make things comfy for myself. The involvement from AI in these changes ranges from "assisted", to "completely implemented." (A human reviews most of the code and wrote this README, though.)

The most notable changes missing from upstream but exist in this fork are:

- Support for large libraries
- Stability improvements
- In-game screenshots (and uploading to Steam)
- Updated gbe-fork
- Most advertising is removed
- In-app OCR translation using AICore / MLKit
- Some cosmetic changes

Worth noting is that both telemetry and "known config" / compatibility checks, etc. are disabled both via explicit removal of features and by shimming to ensure that the app does not reach out to these services. If you want to use those features, you should use upstream's version.

I rebase against upstream every once in a while. I do not provide support or binaries. Additionally, I will not propose any changes upstream myself. If you want to use this fork, you will have to compile it yourself.