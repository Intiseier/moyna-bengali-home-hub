# Third-party notices

Moyna's own code is MIT-licensed (see `LICENSE`). It bundles or depends on:

| Component | Where | License |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v2.0.1: native libraries (`libsherpa-onnx-*.so`) and Kotlin API files | `app/src/main/jniLibs/armeabi-v7a/`, `app/src/main/java/com/k2fsa/sherpa/onnx/` | Apache-2.0 (© Xiaomi Corporation and contributors) |
| Keyword-spotting model `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` (from sherpa-onnx's `kws-models` release) | `app/src/main/assets/kws/` (encoder, decoder, joiner, tokens) | Apache-2.0 (k2-fsa / sherpa-onnx) |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) (shipped with sherpa-onnx) | `app/src/main/jniLibs/armeabi-v7a/libonnxruntime.so` | MIT (© Microsoft Corporation) |
| [OkHttp](https://github.com/square/okhttp) 4.12 | Gradle dependency | Apache-2.0 |
| Gradle wrapper | `gradle/wrapper/` | Apache-2.0 |

The short Bengali voice messages in `app/src/main/assets/clips/` were generated with the Google
Gemini API (voice "Kore") for this project.

Moyna talks to the **Google Gemini API**. Using it is subject to Google's terms; you need your own API key.
