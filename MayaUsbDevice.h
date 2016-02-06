#pragma once

#include <libusb-1.0/libusb.h>
#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <atomic>
#include <thread>

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
  static constexpr size_t BUFFER_LEN = 16384;

  static libusb_context* _usb;

  libusb_device_handle* _hnd;
  MayaUsbDeviceId _id;
  std::string _manufacturer;
  std::string _product;
  uint8_t _inEndpoint;
  uint8_t _outEndpoint;
  std::shared_ptr<InterruptibleThread> _worker;

  int16_t getControlInt16(uint8_t request);
  void sendControl(uint8_t request);
  void sendControlString(uint8_t request, uint16_t index, std::string str);
  void cleanupWorker();

public:
  MayaUsbDevice(uint16_t vid, uint16_t pid);
  MayaUsbDevice(
    std::vector<MayaUsbDeviceId> ids = MayaUsbDeviceId::getAoapIds());
  ~MayaUsbDevice();
  std::string getDescription();
  void convertToAccessory();
  bool waitHandshakeAsync(std::function<void(bool)> callback);

  static void initUsb();
  static void exitUsb();
};
