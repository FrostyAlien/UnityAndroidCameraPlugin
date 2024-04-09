# UnityAndroidCameraPlugin

This is a Android Library that allows Unity to get the camera preview frames in real time. 
It is based on the Camera2 API and OpenGL ES.
The library will get the camera preview frames and pass them to Unity as a YUV texture.
Unity will then convert the YUV texture to RGB texture and display it on the screen.

## How to build the library
```shell
./gradlew YUV420_888:assemble
```