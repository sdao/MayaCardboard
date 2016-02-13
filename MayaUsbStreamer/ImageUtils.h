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

    unsigned char* lOut[] = { lDest, lDest + srcWidth * DEST_COMPS };
    unsigned char* rOut[] = { rDest, rDest + srcWidth * DEST_COMPS };

    lOut[0][0] = srcRows[0][0] * 255.999f;
    lOut[0][1] = srcRows[0][1] * 255.999f;
    lOut[0][2] = srcRows[0][2] * 255.999f;
    lOut[1][0] = srcRows[1][4] * 255.999f;
    lOut[1][1] = srcRows[1][5] * 255.999f;
    lOut[1][2] = srcRows[1][6] * 255.999f;

    rOut[0][0] = srcRows[0][4] * 255.999f;
    rOut[0][1] = srcRows[0][5] * 255.999f;
    rOut[0][2] = srcRows[0][6] * 255.999f;
    rOut[1][0] = srcRows[1][0] * 255.999f;
    rOut[1][1] = srcRows[1][1] * 255.999f;
    rOut[1][2] = srcRows[1][2] * 255.999f;
  }

  inline void processBlockUchar(unsigned char* srcBlock,
      size_t srcWidth, size_t col, unsigned char* lDest, unsigned char* rDest) {
    unsigned char* srcRows[] = {
      srcBlock,
      srcBlock + srcWidth * SRC_COMPS
    };

    unsigned char* lOut[] = { lDest, lDest + srcWidth * DEST_COMPS };
    unsigned char* rOut[] = { rDest, rDest + srcWidth * DEST_COMPS };

    lOut[0][0] = srcRows[0][0];
    lOut[0][1] = srcRows[0][1];
    lOut[0][2] = srcRows[0][2];
    lOut[1][0] = srcRows[1][4];
    lOut[1][1] = srcRows[1][5];
    lOut[1][2] = srcRows[1][6];

    rOut[0][0] = srcRows[0][4];
    rOut[0][1] = srcRows[0][5];
    rOut[0][2] = srcRows[0][6];
    rOut[1][0] = srcRows[1][0];
    rOut[1][1] = srcRows[1][1];
    rOut[1][2] = srcRows[1][2];
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

    // Move in 2x2 blocks through the image.
    for (int row = 0; row < srcHeight; row += 2) {
      for (int col = 0; col < srcWidth; col += 2) {
        T* srcBlock = getPixelPtr<T, SRC_COMPS>(srcData, srcWidth, row, col);
        unsigned char* lDest = getPixelPtr<unsigned char, DEST_COMPS>(
            dest, srcWidth, row, col / 2);
        unsigned char* rDest = getPixelPtr<unsigned char, DEST_COMPS>(
            dest, srcWidth, row, col / 2 + srcWidth / 2);

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
