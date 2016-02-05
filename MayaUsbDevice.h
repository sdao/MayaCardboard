#pragma once

#include <libusb-1.0/libusb.h>
#include <cstdint>
#include <string>
#include <vector>
#include <functional>
#include <thread>

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
  libusb_transfer* _currentTransfer;
  std::function<void(bool)> _currentTransferCallback;
  unsigned char* _currentTransferBuffer;
  MayaUsbDeviceId _id;
  std::string _manufacturer;
  std::string _product;
  uint8_t _inEndpoint;
  uint8_t _outEndpoint;

  int16_t getControlInt16(uint8_t request);
  void sendControl(uint8_t request);
  void sendControlString(uint8_t request, uint16_t index, std::string str);
  void cleanupTransfer();

  static void LIBUSB_CALL handshakeCallback(libusb_transfer* transfer);

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
