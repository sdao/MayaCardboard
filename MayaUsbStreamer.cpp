#include <maya/MObject.h>
#include <maya/MPxCommand.h>
#include <maya/MFnPlugin.h>
#include <maya/MSyntax.h>
#include <maya/MGlobal.h>
#include <maya/M3dView.h>
#include <maya/MString.h>
#include <maya/MViewport2Renderer.h>
#include <maya/MDrawContext.h>
#include <maya/MArgDatabase.h>
#include <libusb-1.0/libusb.h>
#include <memory>
#include <chrono>
#include <thread>
#include <atomic>

#include "MayaUsbDevice.h"

/**
 * Note: you will need to set your udev rules to allow user access to your
 * Android device.
 */

#define CONNECT_COMMAND_NAME "usbConnect"
#define STATUS_COMMAND_NAME "usbStatus"
#define DISCONNECT_COMMAND_NAME "usbDisconnect"

#define CALLBACK_NAME "MayaUsbStreamer_PostRender"

class MayaUsbStreamer {
  static int _debugFrameNum;
  static std::atomic_bool _handshake;
  static std::shared_ptr<MayaUsbDevice> _usbDevice;

public:
  static void createDevice() {
    _usbDevice = std::make_shared<MayaUsbDevice>();
    _usbDevice->waitHandshakeAsync([](bool success) {
      _handshake.store(true);
    });
  }
  static std::shared_ptr<MayaUsbDevice> getDevice() { return _usbDevice; }
  static bool registerNotifications() {
    MHWRender::MRenderer *renderer = MHWRender::MRenderer::theRenderer();
    if (renderer) {
      renderer->addNotification(captureCallback,
        CALLBACK_NAME,
        MHWRender::MPassContext::kEndRenderSemantic,
        nullptr);
      return true;
    }
    return false;
  }
  static bool isConnected() { return _usbDevice != nullptr; }
  static bool isHandshakeComplete() { return _handshake.load(); }
  static void cleanup() {
    _handshake.store(false);
    _usbDevice = nullptr;
    MHWRender::MRenderer *renderer = MHWRender::MRenderer::theRenderer();
    if (renderer) {
      renderer->removeNotification(CALLBACK_NAME,
        MHWRender::MPassContext::kEndRenderSemantic);
    }
  }
  static void captureCallback(MHWRender::MDrawContext &context,
      void* clientData);
};

int MayaUsbStreamer::_debugFrameNum(0);
std::atomic_bool MayaUsbStreamer::_handshake(false);
std::shared_ptr<MayaUsbDevice> MayaUsbStreamer::_usbDevice(nullptr);

class UsbConnectCommand : public MPxCommand {
public:
  UsbConnectCommand();
  static void* creator();
  static MSyntax newSyntax();
  virtual MStatus doIt(const MArgList& args);
};

class UsbStatusCommand : public MPxCommand {
public:
  UsbStatusCommand() {}
  static void* creator() { return new UsbStatusCommand(); }
  static MSyntax newSyntax() { return MSyntax(); }
  virtual MStatus doIt(const MArgList& args) {
    if (MayaUsbStreamer::isConnected()) {
      std::string desc = MayaUsbStreamer::getDevice()->getDescription();
      MGlobal::displayInfo(desc.c_str());
      return MStatus::kSuccess;
    } else {
      MGlobal::displayError("No USB device connected");
      return MStatus::kFailure;
    }
  }
};

class UsbDisconnectCommand : public MPxCommand {
public:
  UsbDisconnectCommand() {}
  static void* creator() { return new UsbDisconnectCommand(); }
  static MSyntax newSyntax() { return MSyntax(); }
  virtual MStatus doIt(const MArgList& args) {
    if (MayaUsbStreamer::isConnected()) {
      MayaUsbStreamer::cleanup();
      MGlobal::displayInfo("USB device disconnected");
      return MStatus::kSuccess;
    } else {
      MGlobal::displayError("No USB device connected");
      return MStatus::kFailure;
    }
  }
};

UsbConnectCommand::UsbConnectCommand() {}

void* UsbConnectCommand::creator() {
  return new UsbConnectCommand();
}

MSyntax UsbConnectCommand::newSyntax() {
  MSyntax syntax;
  syntax.enableEdit(false);
  syntax.enableQuery(false);
  syntax.addFlag("-id", "-deviceId", MSyntax::kString, MSyntax::kString);
  return syntax;
}

MStatus UsbConnectCommand::doIt(const MArgList& args) {
  MArgDatabase argData(syntax(), args);

  if (MayaUsbStreamer::isConnected()) {
    MGlobal::displayError("Already connected");
    return MStatus::kFailure;
  }

  MString vid;
  if (!argData.getFlagArgument("-id", 0, vid) != MStatus::kSuccess) {
    MGlobal::displayError("Error parsing -id VID component");
    return MStatus::kFailure;
  }

  MString pid;
  if (!argData.getFlagArgument("-id", 1, pid) != MStatus::kSuccess) {
    MGlobal::displayError("Error parsing -id PID component");
    return MStatus::kFailure;
  }

  int vidInt = std::stoi(vid.asChar(), 0, 16);
  int pidInt = std::stoi(pid.asChar(), 0, 16);
  std::cout << "vid=" << vidInt << ", pid=" << pidInt << std::endl;

  MHWRender::MRenderer *renderer = MHWRender::MRenderer::theRenderer();
  if (!renderer) {
    MGlobal::displayError("VP2 renderer not initialized.");
    return MStatus::kFailure;
  }

  try {
    // Switch the device to accessory mode (if it's not yet an accessory).
    try {
      auto tempDevice = std::make_shared<MayaUsbDevice>(vidInt, pidInt);
      tempDevice->convertToAccessory();

      // Wait for device re-renumeration.
      std::this_thread::sleep_for(std::chrono::seconds(1));
    } catch (const std::runtime_error& err) {
      std::cout << err.what() << std::endl;
    }

    MayaUsbStreamer::createDevice();
    MayaUsbStreamer::registerNotifications();

    MGlobal::displayInfo("USB device connected!");
    return MStatus::kSuccess;
  } catch (...) {
    MGlobal::displayError("Could not connect");
    return MStatus::kFailure;
  }
}

void MayaUsbStreamer::captureCallback(MHWRender::MDrawContext &context,
    void* clientData) {
  MString destName;
  context.renderingDestination(destName);
  std::cout << _debugFrameNum++ << " " << destName.asChar() << std::endl;

  MHWRender::MRenderer* renderer = MHWRender::MRenderer::theRenderer();
  if (!renderer) {
    return;
  }

  MHWRender::MTextureManager* textureManager = renderer->getTextureManager();
  MHWRender::MTexture* colorTexture =
      context.copyCurrentColorRenderTargetToTexture();

  if (colorTexture) {
    int row, slice;
    void* rawData = colorTexture->rawData(row, slice);

    if (MayaUsbStreamer::isHandshakeComplete()) {
      bool status = MayaUsbStreamer::getDevice()->sendDataSync(rawData, slice);
      if (status) {
        std::cout << "  -> " << row << "/" << slice << std::endl;
      } else {
        MayaUsbStreamer::cleanup();
        MGlobal::displayError("Streaming error, USB device disconnected");
      }
    }

    MHWRender::MTexture::freeRawData(rawData);
    textureManager->releaseTexture(colorTexture);
  }
}

MStatus initializePlugin(MObject obj) {
  MStatus status;
  MFnPlugin plugin(obj, "SiriusCybernetics", "1.0", "Any");

  status = plugin.registerCommand(CONNECT_COMMAND_NAME,
      UsbConnectCommand::creator,
      UsbConnectCommand::newSyntax);
  if (!status) {
    status.perror("registerCommand");
    return status;
  }

  status = plugin.registerCommand(STATUS_COMMAND_NAME,
      UsbStatusCommand::creator,
      UsbStatusCommand::newSyntax);
  if (!status) {
    status.perror("registerCommand");
    return status;
  }

  status = plugin.registerCommand(DISCONNECT_COMMAND_NAME,
      UsbDisconnectCommand::creator,
      UsbDisconnectCommand::newSyntax);
  if (!status) {
    status.perror("registerCommand");
    return status;
  }

  MayaUsbDevice::initUsb();

  return status;
}

MStatus uninitializePlugin(MObject obj) {
  MayaUsbStreamer::cleanup();

  MStatus status;
  MFnPlugin plugin(obj);

  status = plugin.deregisterCommand(CONNECT_COMMAND_NAME);
  if (!status) {
    status.perror("deregisterCommand");
    return status;
  }

  status = plugin.deregisterCommand(STATUS_COMMAND_NAME);
  if (!status) {
    status.perror("deregisterCommand");
    return status;
  }

  status = plugin.deregisterCommand(DISCONNECT_COMMAND_NAME);
  if (!status) {
    status.perror("deregisterCommand");
    return status;
  }

  MayaUsbDevice::exitUsb();

  return status;
}
