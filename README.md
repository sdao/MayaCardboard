MayaCardboard
=============

Explore a Maya scene using Google Cardboard. Linux only.

This project has two parts: a Maya plugin and an Android client. The Maya
plugin uses `libusb` to connect to an Android device in accessory mode. It
compresses the contents of a stereo viewport using `libjpeg-turbo` and then
streams the frames over USB to the Android device.
The Android client receives the MJPEG stream from the Maya plugin and displays
the stream using the Google Cardboard SDK.

You should be able to acquire `libusb` and `libjpeg-turbo` from the package
manager of your Linux distribution. For reference, I used Fedora 23.

Maya plugin (`MayaUsbStreamer`)
-------------------------------
The Maya plugin exposes three commands, `usbConnect`, `usbStatus`, and
`usbDisconnect`.
- `usbConnect`: this command requires two parameters, `-id` and `-sp`. The
  `-id` parameter must be two strings, representing the USB VID and PID of the
  connected Android device in hex format, e.g. `-id "22b8" "2e82"` for a Moto G
  (3rd generation). The `-sp` parameter is the name of a stereo panel, e.g.
  `-sp StereoPanel` for the default stereo panel.
  - Example command: `usbConnect -id "22b8" "2e82" -sp StereoPanel`.
- `usbStatus`: returns information about the currently-connected USB device.
- `usbDisconnect`: stops the stream if a USB device is connected.

Note: you must edit your `udev` rules to allow your device's VID/PID, as well
as allowing all devices with VID `18d1` (Google's VID), which is used when the
device is in accessory mode. Use `lsusb` to determine your device's VID/PID.

The stereo panel that you use for the `-sp` parameter must be set to
"checkerboard" stereo output. The plugin will take the checkerboard-formatted
image and reconstitute separate left and right images. It then combines the
two left and right images side-by-side into one single frame to send to the
Android device.

### Streaming details! ###
When the plugin receives the device's handshake, it begins a send loop on a
separate thread. The send loop sleeps until Maya reports that the viewport
has redrawn. At that point, the frame is queued, and a monitor is used to
notify and wake up the send loop thread. The send loop then compresses the
frame into a JPEG and performs a USB bulk transfer (note that isochronous
transfers are not available over Android accessory protocol). If the send loop
is busy when another frame is queued, that frame will be discarded.

Android client (`MayaUsbReceiver`)
----------------------------------
This is an Android app that requires OpenGL ES 2. To connect with Maya, first
send the `usbConnect` command from Maya, then click the "Send Handshake" button
in the client. If all goes well, the Maya plugin will begin streaming.

The Android client receives frames with the left-eye image on the left half and
the right-eye image on the right half. It draws this in OpenGL using a quad
that displays the left half of the render texture for the left eye and vice
versa for the right eye.

TODOS
-----
- The render size is currently hardcoded in the plugin as 1280x720, which
  generates a 640x720 image for each eye. (Note that the plugin will ask Maya
  to render viewports at _1280x1440_ in order to produce the correct per-eye
  aspect ratio.)
- There is no head-tracking yet for rotating the camera in the Maya scene.
- The stereoscopic camera is not setup using the Cardboard viewer parameters.
