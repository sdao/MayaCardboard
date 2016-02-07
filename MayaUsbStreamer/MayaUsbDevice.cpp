#include "MayaUsbDevice.h"
#include <boost/endian/conversion.hpp>
#include <stdexcept>
#include <sstream>
#include <iostream>
#include <iomanip>
#include <cstring>

libusb_context* MayaUsbDevice::_usb(nullptr);
tjhandle MayaUsbDevice::_jpegCompressor(nullptr);

MayaUsbDevice::MayaUsbDevice(uint16_t vid, uint16_t pid)
    : MayaUsbDevice({ MayaUsbDeviceId(vid, pid) }) {}

MayaUsbDevice::MayaUsbDevice(std::vector<MayaUsbDeviceId> ids)
    : _hnd(nullptr),
      _worker(nullptr),
      _handshake(false),
      _sendReady(false),
      _rgbImageBuffer(new unsigned char[RGB_IMAGE_SIZE]),
      _jpegBuffer(nullptr) {
  int status;

  for (const MayaUsbDeviceId& id : ids) {
    libusb_device_handle* tempHnd =
        libusb_open_device_with_vid_pid(_usb, id.vid, id.pid);
    if (tempHnd != nullptr) {
      _id = id;
      _hnd = tempHnd;
      break;
    }
  }
  if (_hnd == nullptr) {
    throw std::runtime_error("Could not create device with given VIDs/PIDs");
  }

  libusb_device* dev = libusb_get_device(_hnd);

  libusb_device_descriptor desc;
  status = libusb_get_device_descriptor(dev, &desc);
  if (status < 0) {
    libusb_close(_hnd);
    throw std::runtime_error("Could not get device descriptor");
  }

  char manufacturerString[256];
  status = libusb_get_string_descriptor_ascii(
    _hnd,
    desc.iManufacturer,
    reinterpret_cast<unsigned char*>(manufacturerString),
    sizeof(manufacturerString)
  );
  if (status < 0) {
    libusb_close(_hnd);
    throw std::runtime_error("Could not get manufacturer string");
  }

  char productString[256];
  status = libusb_get_string_descriptor_ascii(
    _hnd,
    desc.iProduct,
    reinterpret_cast<unsigned char*>(productString),
    sizeof(productString)
  );
  if (status < 0) {
    libusb_close(_hnd);
    throw std::runtime_error("Could not get product string");
  }

  _manufacturer = std::string(manufacturerString);
  _product = std::string(productString);

  libusb_config_descriptor* configDesc;
  status = libusb_get_active_config_descriptor(dev, &configDesc);
  if (status < 0) {
    libusb_close(_hnd);
    throw std::runtime_error("Could not get configuration descriptor");
  }

  const libusb_interface& interface = configDesc->interface[0];
  const libusb_interface_descriptor& interfaceDesc = interface.altsetting[0];

  for (int i = 0; i < interfaceDesc.bNumEndpoints; ++i) {
    const libusb_endpoint_descriptor& endpoint = interfaceDesc.endpoint[i];
    bool in = (endpoint.bEndpointAddress & 0b10000000) == LIBUSB_ENDPOINT_IN;
    bool out = (endpoint.bEndpointAddress & 0b10000000) == LIBUSB_ENDPOINT_OUT;

    if (in) {
      _inEndpoint = endpoint.bEndpointAddress;
    } else if (out) {
      _outEndpoint = endpoint.bEndpointAddress;
    }
  }

  libusb_free_config_descriptor(configDesc);
}

MayaUsbDevice::~MayaUsbDevice() {
  delete[] _rgbImageBuffer;
  if (_jpegBuffer != nullptr) {
    tjFree(_jpegBuffer);
  }

  if (_worker && !_worker->isCancelled()) {
    _worker->cancel(); // Cancel any running InterruptibleThread.
    _sendCv.notify_one(); // Wake up the send loop so it can cancel.
    // Wait 1s since our loop checks every 500ms for cancel flag.
    std::this_thread::sleep_for(std::chrono::seconds(1));
    libusb_close(_hnd);
  } else {
    libusb_close(_hnd);
  }
}

std::string MayaUsbDevice::getDescription() {
  std::ostringstream os;
  os << std::setfill('0') << std::hex
     << std::setw(4) << _id.vid << ":"
     << std::setw(4) << _id.pid << " "
     << std::dec << std::setfill(' ');

  // Print device manufacturer and product name.
  os << _manufacturer << " " << _product;
  return os.str();
}

int16_t MayaUsbDevice::getControlInt16(uint8_t request) {
  int16_t data;
  if (libusb_control_transfer(
      _hnd,
      LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_VENDOR,
      request,
      0,
      0,
      reinterpret_cast<unsigned char*>(&data),
      sizeof(data),
      0) < 0) {
    throw std::runtime_error("Could not get request");
  }

  return data;
}

void MayaUsbDevice::sendControl(uint8_t request) {
  if (libusb_control_transfer(
      _hnd,
      LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_VENDOR,
      request,
      0,
      0,
      nullptr,
      0,
      0) < 0) {
    throw new std::runtime_error("Could not send request");
  }
}

void MayaUsbDevice::sendControlString(uint8_t request, uint16_t index,
    std::string str) {
  char temp[256];
  str.copy(&temp[0], sizeof(temp));
  if (libusb_control_transfer(
      _hnd,
      LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_VENDOR,
      request,
      0,
      index,
      reinterpret_cast<unsigned char*>(temp),
      str.size(),
      0) < 0) {
    throw new std::runtime_error("Could not send request");
  }
}

void MayaUsbDevice::convertToAccessory() {
  // Get protocol.
  int16_t protocolVersion = getControlInt16(51);
  if (protocolVersion < 1) {
    throw new std::runtime_error("AOA protocol version < 1");
  }

  // Send manufacturer string.
  sendControlString(52, 0, "SiriusCybernetics");

  // Send model string.
  sendControlString(52, 1, "MayaUsb");

  // Send description.
  sendControlString(52, 2, "Maya USB streaming");

  // Send version.
  sendControlString(52, 3, "0.42");

  // Send URI.
  sendControlString(52, 4, "https://sdao.me");

  // Send serial number.
  sendControlString(52, 5, "42");

  // Start accessory.
  sendControl(53);
}

bool MayaUsbDevice::waitHandshakeAsync(std::function<void(bool)> callback) {
  if (_inEndpoint == 0) {
    return false;
  }

  if (_handshake.load()) {
    return false;
  }

  _worker = std::make_shared<InterruptibleThread>(
    [=](const InterruptibleThread::SharedAtomicBool cancel) {
      unsigned char* inputBuffer = new unsigned char[BUFFER_LEN];
      int i = 0;
      int read = 0;
      int status = LIBUSB_ERROR_TIMEOUT;
      bool cancelled;
      while (!(cancelled = cancel->load()) && status == LIBUSB_ERROR_TIMEOUT) {
        std::cout << i++ << " Waiting..." << std::endl;
        status = libusb_bulk_transfer(_hnd,
            _inEndpoint,
            inputBuffer,
            BUFFER_LEN,
            &read,
            500);
      }
      delete[] inputBuffer;

      if (cancelled) {
        std::cout << "Cancelled!" << std::endl;
      } else {
        bool success = read > 0;
        std::cout << "Received handshake, success=" << success << std::endl;

        _handshake.store(success);
        callback(success);
      }

      cancel->store(true);
    }
  );

  return true;
}

bool MayaUsbDevice::isHandshakeComplete() {
  return _handshake.load();
}

bool MayaUsbDevice::beginSendLoop(std::function<void()> failureCallback) {
  if (_outEndpoint == 0) {
    return false;
  }

  if (!_handshake.load()) {
    return false;
  }

  _worker = std::make_shared<InterruptibleThread>(
    [=](const InterruptibleThread::SharedAtomicBool cancel) {
      while (true) {
        std::unique_lock<std::mutex> lock(_sendMutex);
        _sendCv.wait(lock, [&]{
          return _sendReady || cancel->load();
        });

        if (cancel->load()) {
          int written = 0;

          // Write 0 buffer size.
          uint32_t bytes = 0;
          libusb_bulk_transfer(_hnd,
              _outEndpoint,
              reinterpret_cast<unsigned char*>(&bytes),
              4,
              &written,
              500);

          // Ignore if written or not.
          return;
        } else {
          int written = 0;

          // Write size of JPEG (32-bit int).
          uint32_t bytes = _jpegBufferSize;
          bytes = boost::endian::native_to_big(bytes);
          libusb_bulk_transfer(_hnd,
              _outEndpoint,
              reinterpret_cast<unsigned char*>(&bytes),
              4,
              &written,
              500);
          if (written < 4) {
            failureCallback();
            return;
          }

          // Write JPEG in BUFFER_LEN chunks.
          for (int i = 0; i < _jpegBufferSize; i += BUFFER_LEN) {
            written = 0;

            int chunk = std::min(BUFFER_LEN, _jpegBufferSize - i);
            libusb_bulk_transfer(_hnd,
                _outEndpoint,
                _jpegBuffer + i,
                chunk,
                &written,
                500);

            if (written < chunk) {
              failureCallback();
              return;
            }
          }
        }

        // Only reset send flag if successful.
        _sendReady = false;
      }
      std::cout << "Send loop ended" << std::endl;
    }
  );

  return true;
}

int MayaUsbDevice::sendRgbaFloat32Sync(void* data,
  MHWRender::MTextureDescription desc) {
  size_t rgbImageSize = desc.fWidth * desc.fHeight * 3 /* RGB bytes */;
  if (rgbImageSize > RGB_IMAGE_SIZE) {
    return 0;
  }

  float* rgbaData = reinterpret_cast<float*>(data);
  for (int i = 0; i < rgbImageSize; ++i) {
    int pixel = i / 3;
    int offset = i % 3;
    int rgbaIndex = pixel * 4 + offset;
    float val = rgbaData[rgbaIndex];
    _rgbImageBuffer[i] = (unsigned char) (val * 255.9999f);
  }

  // Convert _rgbImageBuffer to JPEG.
  if (_sendMutex.try_lock()) {
    std::lock_guard<std::mutex> lock(_sendMutex, std::adopt_lock);
    if (!_sendReady) {
      std::cout << "$" << std::endl;
      tjCompress2(_jpegCompressor,
          _rgbImageBuffer,
          desc.fWidth,
          0,
          desc.fHeight,
          TJPF_RGB,
          &_jpegBuffer,
          &_jpegBufferSize,
          TJSAMP_420,
          80 /* quality 1 to 100 */,
          0);
      _sendReady = true;
      _sendCv.notify_one();
      return _jpegBufferSize;
    }
  }

  std::cout << "#" << std::endl;
  return 0;
}

void MayaUsbDevice::initUsb() {
  if (_usb) {
    return;
  }
  libusb_init(&_usb);
  std::cout << "libusb INIT" << std::endl;
}

void MayaUsbDevice::exitUsb() {
  if (_usb) {
    libusb_exit(_usb);
    std::cout << "libusb EXIT" << std::endl;
  }
  _usb = nullptr;
}

void MayaUsbDevice::initJpeg() {
  if (_jpegCompressor) {
    return;
  }
  _jpegCompressor = tjInitCompress();
  std::cout << "TurboJPEG INIT" << std::endl;
}

void MayaUsbDevice::exitJpeg() {
  if (_jpegCompressor) {
    tjDestroy(_jpegCompressor);
    std::cout << "TurboJPEG EXIT" << std::endl;
  }
  _jpegCompressor = nullptr;
}
