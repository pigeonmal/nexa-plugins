# Third-party notices

The Media3 FFmpeg renderer and JNI wrapper are adapted from AndroidX Media
[`libraries/decoder_ffmpeg`](https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg), including the video renderer implementation from
[AndroidX Media pull request 1591](https://github.com/androidx/media/pull/1591). Those files retain
their Apache License 2.0 headers. The complete AndroidX license is in
`ANDROIDX-LICENSE.txt`.

The bundled FFmpeg shared libraries are built from the unmodified upstream
FFmpeg 9.0.2 source archive in `third_party/ffmpeg-9.0.2.tar.xz`. FFmpeg is
configured with GPL, LGPLv3, and nonfree components disabled; this build is
under LGPL 2.1 or later. The build script checks those configure flags and
includes the exact build configuration in the generated AAR at
`assets/nexa/ffmpeg-build.txt`.

The pixel conversion library is libyuv from Chromium's libyuv repository at
commit `b25fa8992056629c99d7815516e7be6b82509897`. It is licensed under the
BSD 3-Clause license; see `LIBYUV-LICENSE.txt`.

The AndroidX wrapper code and FFmpeg libraries are distributed as distinct
shared libraries. Applications distributing the generated AAR must comply
with the applicable licenses and provide access to the corresponding FFmpeg
source and build information.
