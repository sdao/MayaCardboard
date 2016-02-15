#pragma once

namespace ImageUtils {

  static constexpr size_t SRC_COMPS = 4; // RGBA
  static constexpr size_t DEST_COMPS = 4; // RGBX

  template <typename T>
  using ProcessBlockFunc =
      void(T*, size_t, size_t, unsigned char*, unsigned char*);

  template <typename T, size_t comps>
  inline T* getPixelPtr(T* buf, size_t bufWidth, size_t row, size_t col) {
    return buf + (row * bufWidth * comps) + (col * comps);
  }

  inline void processBlockFloat(float* srcBlock, size_t srcWidth,
      size_t col, unsigned char* lDest, unsigned char* rDest) {
    float* srcRows[] = {
      srcBlock,
      srcBlock + srcWidth * SRC_COMPS
    };

    lDest[0] = (srcRows[0][0] + srcRows[1][4]) * 127.999f;
    lDest[1] = (srcRows[0][1] + srcRows[1][5]) * 127.999f;
    lDest[2] = (srcRows[0][2] + srcRows[1][6]) * 127.999f;

    rDest[0] = (srcRows[0][4] + srcRows[1][0]) * 127.999f;
    rDest[1] = (srcRows[0][5] + srcRows[1][1]) * 127.999f;
    rDest[2] = (srcRows[0][6] + srcRows[1][2]) * 127.999f;
  }

  inline void processBlockUchar(unsigned char* srcBlock,
      size_t srcWidth, size_t col, unsigned char* lDest, unsigned char* rDest) {
    unsigned char* srcRows[] = {
      srcBlock,
      srcBlock + srcWidth * SRC_COMPS
    };

    lDest[0] = (srcRows[0][0] + srcRows[1][4]) / 2;
    lDest[1] = (srcRows[0][1] + srcRows[1][5]) / 2;
    lDest[2] = (srcRows[0][2] + srcRows[1][6]) / 2;

    rDest[0] = (srcRows[0][4] + srcRows[1][0]) / 2;
    rDest[1] = (srcRows[0][5] + srcRows[1][1]) / 2;
    rDest[2] = (srcRows[0][6] + srcRows[1][2]) / 2;
  }

  template <typename T, ProcessBlockFunc<T> processBlock>
  bool decomposeCheckerboardStereo(void* src, size_t srcWidth, size_t srcHeight,
      unsigned char* dest, size_t destSize) {
    // Enforce src buffer dimensions.
    if (srcWidth % 2 != 0 || srcHeight % 2 != 0) {
      return false;
    }

    // Enforce dest buffer capacity.
    size_t spaceRequired = srcWidth * srcHeight * DEST_COMPS;
    if (destSize < spaceRequired) {
      return false;
    }

    // Cast buffer to correct format.
    T* srcData = reinterpret_cast<T*>(src);

    // Move in 2x2 blocks through the source and map to 2x1 blocks.
    for (int row = 0; row < srcHeight; row += 2) {
      for (int col = 0; col < srcWidth; col += 2) {
        T* srcBlock = getPixelPtr<T, SRC_COMPS>(srcData, srcWidth, row, col);
        unsigned char* lDest = getPixelPtr<unsigned char, DEST_COMPS>(
            dest, srcWidth, row / 2, col / 2);
        unsigned char* rDest = getPixelPtr<unsigned char, DEST_COMPS>(
            dest, srcWidth, row / 2, col / 2 + srcWidth / 2);

        processBlock(srcBlock, srcWidth, col, lDest, rDest);
      }
    }

    return true;
  }

  bool decomposeCheckerboardStereoFloat(void* src, size_t srcWidth,
      size_t srcHeight, unsigned char* dest, size_t destSize) {
    return decomposeCheckerboardStereo<float, processBlockFloat>(
        src, srcWidth, srcHeight, dest, destSize);
  }

  bool decomposeCheckerboardStereoUchar(void* src, size_t srcWidth,
      size_t srcHeight, unsigned char* dest, size_t destSize) {
    return decomposeCheckerboardStereo<unsigned char, processBlockUchar>(
        src, srcWidth, srcHeight, dest, destSize);
  }

}
