# UnityAndroidCameraPlugin

This is an Android Library that allows Unity to get the camera preview frames in real time.
It is based on the Camera2 API. The basic idea is to use ImageReader to get the camera preview frames in YUV format, and then pass them to Unity as a YUV texture.

Unity will then convert the YUV texture to an RGB texture and display it on the screen.
## How to build the library
```shell
./gradlew YUV420_888:assemble
```

## How to use it
1. Use it as an AndroidJavaObject in Unity
2. You need two Texture2D objects in Unity to display the camera preview frames. One is for Y plane (TextureFormat.R8), the other is for UV plane (TextureFormat.RG16).
3. You need a shader to convert YUV texture to RGB texture. Check YCbCr to RGB Conversion matrix.
4. You need to set the shader to the material of the RawImages that you want to display the camera preview frames.
5. Done

## The status of the project
This is a small part of my research project prototype. It is not ready for production. And it only tested on Samsung S23 and S21.
Use it at your own risk. \
The full project may be released in the future if requested by the academic peer reviewers.