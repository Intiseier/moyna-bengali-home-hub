Wake word model: sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20 (k2-fsa/sherpa-onnx, Apache-2.0),
full-precision chunk-16 files (the int8/"mobile" variants hang or run too slowly on this 32-bit phone).

keywords.txt lists how "Moyna" sounds, written in English phonemes (ARPAbet), one spelling per line:
  M OY1 N AH0 = moy-nuh    M OY1 N AA1 = moy-naa    M AY1 N AA1 = my-naa    M AY1 N AH0 = my-nuh
The part after @ is just a label. Add or remove lines to change what counts as "Moyna".
