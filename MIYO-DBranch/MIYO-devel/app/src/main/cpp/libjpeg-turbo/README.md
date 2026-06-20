# libjpeg-turbo Integration

The native JPEG decoder requires libjpeg-turbo source files.

## Setup
Clone libjpeg-turbo into this directory:
```
git clone https://github.com/libjpeg-turbo/libjpeg-turbo.git --depth 1 --branch 3.1.0 .
```

Or download from: https://github.com/libjpeg-turbo/libjpeg-turbo/releases

## Minimal Setup (header only, no JPEG support)
If you cannot include the full libjpeg-turbo source, the CMakeLists.txt will skip JPEG support and only compile the YUV converter and init modules.

## Android ABIs
The native library will compile for all standard Android ABIs:
- arm64-v8a (most devices)
- armeabi-v7a (older devices)
- x86_64 (emulator)
- x86 (emulator)