#pragma once

#include <maya/MTextureManager.h>
#include <libusb-1.0/libusb.h>
#include <turbojpeg.h>
#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>

class InterruptibleThread {
public:
  using SharedAtomicBool = std::shared_ptr<std::atomic_bool>;

  InterruptibleThread(std::function<void(const SharedAtomicBool)> func) {
    _cancel = std::make_shared<std::atomic_bool>(false);
    std::thread thread([=]() {
      func(_cancel);
    });
    thread.detach();
  }

  ~InterruptibleThread() {
    cancel();
  }

  void cancel() { _cancel->store(true); }
  bool isCancelled() { return _cancel->load(); }

private:
  SharedAtomicBool _cancel;
};

struct MayaUsbDeviceId {
  uint16_t vid;
  uint16_t pid;
  MayaUsbDeviceId() : vid(0), pid(0) {}
  MayaUsbDeviceId(uint16_t v, uint16_t p) : vid(v), pid(p) {}
  static std::vector<MayaUsbDeviceId> getAoapIds() {
    return {
      MayaUsbDeviceId(0x18D1, 0x2D00), // accessory
      MayaUsbDeviceId(0x18D1, 0x2D01), // accessory + ADB
    };
  }
};

class MayaUsbDevice {
  static constexpr size_t RGB_IMAGE_SIZE = 1024 * 1024 * 16; // 16 MB.
  static constexpr size_t BUFFER_LEN     = 16384;

  static libusb_context* _usb;
  static tjhandle _jpegCompressor;

  libusb_device_handle* _hnd;

  MayaUsbDeviceId _id;
  std::string _manufacturer;
  std::string _product;
  uint8_t _inEndpoint;
  uint8_t _outEndpoint;

  std::atomic_bool _handshake;

  std::shared_ptr<InterruptibleThread> _receiveWorker;

  std::shared_ptr<InterruptibleThread> _sendWorker;
  bool _sendReady; /* Note: doesn't have to be atomic because we lock. */
  std::mutex _sendMutex;
  std::condition_variable _sendCv;

  unsigned char* _rgbImageBuffer;
  unsigned char* _jpegBuffer;
  size_t _jpegBufferSize;
  size_t _jpegBufferWidth;
  size_t _jpegBufferHeight;

  int16_t getControlInt16(uint8_t request);
  void sendControl(uint8_t request);
  void sendControlString(uint8_t request, uint16_t index, std::string str);

public:
  MayaUsbDevice(uint16_t vid, uint16_t pid);
  MayaUsbDevice(
    std::vector<MayaUsbDeviceId> ids = MayaUsbDeviceId::getAoapIds());
  ~MayaUsbDevice();
  std::string getDescription();
  void convertToAccessory();
  bool waitHandshakeAsync(std::function<void(bool)> callback);
  bool isHandshakeComplete();
  bool beginReadLoop(std::function<void(const unsigned char*)> callback,
      size_t readFrame);
  bool beginSendLoop(std::function<void()> failureCallback);
  bool sendStereo(void* data, MHWRender::MTextureDescription desc);
  static bool supportsRasterFormat(MHWRender::MRasterFormat format);

  static void initUsb();
  static void exitUsb();
  static void initJpeg();
  static void exitJpeg();
};
