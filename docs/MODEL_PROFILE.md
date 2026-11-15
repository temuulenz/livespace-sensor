# SSD MobileNet V1 model and runtime profile

## Provenance and license

Bundled model: genuine TensorFlow COCO SSD MobileNet V1 1.0 quantized, dated 2018-06-29. Downloaded directly over HTTPS from [TensorFlow's model archive](https://storage.googleapis.com/download.tensorflow.org/models/tflite/coco_ssd_mobilenet_v1_1.0_quant_2018_06_29.zip). The official [TensorFlow example README](https://github.com/tensorflow/examples/blob/master/lite/examples/object_detection/ios/README.md) links that exact archive and describes 300×300 RGB input. No retraining, conversion, placeholder or binary modification was performed. Archive contains only detect.tflite and labelmap.txt; labelmap.txt is copied verbatim as labels.txt.

License: Apache 2.0, per the publisher's [TensorFlow SSD MobileNet V1 model listing](https://www.kaggle.com/models/tensorflow/ssd-mobilenet-v1/tfLite). The old ZIP has no embedded license file or rich metadata; the model-family listing supplies the licensing declaration, not an assertion that the newer metadata variant is byte-identical. Apache text and an attribution/source notice ship in assets. TensorFlow runtime licensing is also Apache 2.0; bundled full upstream license text includes third-party notices. COCO training provenance does not grant ownership of training images; none are bundled.

| File | Bytes | SHA-256 |
|---|---:|---|
| detect.tflite | 4,183,312 | e4b118e5e4531945de2e659742c7c590f7536f8d0ed26d135abcfe83b4779d13 |
| labels.txt | 665 | c7e79c855f73cbba9f33d649d60e1676eb0a974021a41696d1ac0d4b7f7e0211 |

Checksum verification is a locally computed integrity record of the HTTPS artifact, not a publisher-signed checksum.

## Verified tensors and quantization

FlatBuffer schema version 3, description `TOCO Converted.`, zero metadata entries. Reproducible host inspection: `python3 tools/inspect_model.py`; output saved in evidence/model-flatbuffer.json. Runtime allocation and tensor introspection on API 23 independently confirmed the following (evidence/runtime-probe.txt):

| Index | Name | Allocated shape | Type | Quantization |
|---|---|---|---|---|
| input 0 | normalized_input_image_tensor | [1,300,300,3] | UINT8 | scale=0.0078125 (1/128), zeroPoint=128 |
| output 0 | TFLite_Detection_PostProcess | [1,10,4] | FLOAT32 | none (Java scale=0, zeroPoint=0) |
| output 1 | TFLite_Detection_PostProcess:1 | [1,10] | FLOAT32 | none |
| output 2 | TFLite_Detection_PostProcess:2 | [1,10] | FLOAT32 | none |
| output 3 | TFLite_Detection_PostProcess:3 | [1] | FLOAT32 | none |

The old FlatBuffer leaves output shapes unspecified; the custom op's allocation resolves them at runtime. Input is RGB NHWC. For a byte q, real input = (q−128)/128. Feed RGB byte values directly; do not additionally normalize then cast or reinterpret as signed INT8 values. This is an 8-bit **UINT8** quantized model, not a signed-INT8-I/O model. Inspection counted 112 UINT8, 60 INT32 and 4 FLOAT32 tensors; INT32 includes quantized convolution biases and shape constants.

Outputs: locations [top,left,bottom,right] in normalized upright model coordinates; zero-based float class IDs; scores; valid detection count, limited to 10. Labels include the leading `???` background entry: output class 0 → labels[1] → person. PersonDetector verifies the tensor names/types/shapes/quantization and person label before inference. Nonfinite outputs are rejected; boxes clamp to [0,1]; degenerate boxes are ignored. Only class 0 above threshold and with box center inside the monitoring area contributes to counts.

FlatBuffer operators (all version 1): CONV_2D ×34, DEPTHWISE_CONV_2D ×13, CONCATENATION ×2, LOGISTIC ×1, RESHAPE ×13, custom TFLite_Detection_PostProcess ×1. CPU runtime successfully invoked all required operators on the phone. The detection postprocess op includes NMS; the app does not repeat it. There are no SELECT_TF_OPS/Flex dependencies.

## Runtime selection and native compatibility

Pinned dependency: `org.tensorflow:tensorflow-lite:2.8.0` (transitive tensorflow-lite-api 2.8.0). CPU only; NNAPI=false, XNNPACK=false; no GPU/delegate/support/task libraries. The official [Android development guide](https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/g3doc/android/development.md) documents API 19 support for this classic runtime family. Actual device execution, rather than this generic claim alone, is the compatibility gate.

AAR inspected from Maven Central: [2.8.0 artifact](https://repo.maven.apache.org/maven2/org/tensorflow/tensorflow-lite/2.8.0/tensorflow-lite-2.8.0.aar). Its manifest comments say built against NDK API 19 and declare targetSdkVersion 19 (not an explicit minSdk). It contains arm64-v8a, armeabi-v7a, x86 and x86_64 JNI libraries. This app deliberately packages **only armeabi-v7a** for the verified phone. AAR ARMv7 JNI size 2,265,808 bytes, SHA-256 b34b0fe7d20ef29adacf68886ef6bb402cd0aff905feefe56fe1083ec0c7aefe. APK inspection confirmed that exact library and no arm64 library; detect.tflite is uncompressed for memory mapping. See evidence/runtime-aars.json and evidence/apk-native.txt.

TFLite **2.14.0 was rejected after physical testing**: despite declared minSdk 19 and an ARMv7 .so, dlopen failed on `__aeabi_memcpy4`. ELF inspection showed imports versioned `LIBC_N`; the Samsung API 23 libc exposes these under `LIBC_PRIVATE`. Older 2.8.0 uses unversioned imports and loaded successfully. 2.5.0 and 2.3.0 binaries were also inspected but not selected or claimed tested. Minimal 2.8.0 device probe: initialization/allocation 22.871333 ms, synthetic neutral input invocation succeeded, interpreter closed. Synthetic probe output is never treated as a camera observation or accuracy test.

AGP 9.5.1 otherwise rejects runtime/API AARs sharing `org.tensorflow.lite`. The supported `android.uniquePackageNames=false` switch is documented in gradle.properties; it leaves a namespace warning and must be revisited before AGP 10. This does not alter Android app ID or disable TLS/security checks. No minSdk override is used.

The pinned runtime predates modern 16 KB-page devices; lint flags dependency x86_64 alignment. The delivered ARMv7 APK is intended for this isolated API 23 endpoint, not claimed validated for modern devices or store deployment. Replacing/upgrading the runtime requires the same physical loading/operator/performance checks.

## Preprocessing and coordinate contract

Read YUV_420_888 planes with each plane's current buffer position, row stride and pixel stride, including crop offsets. Nearest-neighbor sampling maps only 90,000 model pixels into the sensor planes; BT.601 limited-range conversion clamps RGB. Reuse a 270,000-byte direct input buffer plus x/y coordinate maps. No full-frame RGB bitmap, camera pixel copies to storage or raw media export.

Rear rotation = (sensorOrientation − displayRotation + 360) % 360. Rotate into upright coordinates before nonuniformly resizing the whole field to 300×300 (no center crop). Model boxes therefore remain normalized to the full upright field. Preview uses fit-center letterboxing preserving the upright field's aspect ratio; overlay uses the same fit rectangle. Monitoring area is also normalized in this upright field. Changing device rotation changes this coordinate frame; deployment should fix the mounting orientation.

Math/stride tests cover synthetic buffers, not real visual alignment or detection accuracy. Human confirmation with people in view, all rotations, varied lighting/occlusion and the actual venue remains required. The model emits at most 10 detections across all classes, so crowd counts can saturate or undercount even before person filtering. Small/occluded people may be missed; confidence is a mean retained-person score, not calibrated occupancy accuracy.

For an Image with a non-full cropRect, postprocessing first maps the model's normalized crop-relative boxes through the rotated crop bounds into the full upright preview before monitoring-area filtering. A focused unit test verifies this mapping. The physical stream was 640×480; non-full crop behavior is covered by synthetic tests rather than separately measured on the phone.

Downloaded archive SHA-256: a809cd290b4d6a2e8a9d5dad076e0bd695b8091974e0eed1052b480b2f21b6dc.

The publisher's [CPU detection postprocess implementation](https://github.com/tensorflow/tensorflow/blob/v2.8.0/tensorflow/lite/kernels/detection_postprocess.cc) implements the NMS path used by the custom operator. This source was inspected alongside the model's operator list and successful device invocation.
