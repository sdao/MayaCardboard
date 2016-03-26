#pragma once
#include <boost/endian/conversion.hpp>

namespace EndianUtils {

  template <typename T>
  inline T nativeToBig(T x) {
    return boost::endian::native_to_big(x);
  }

  inline float bigToNativeFloat(float x) {
    if (boost::endian::order::native == boost::endian::order::big) {
      return x;
    }

    float swapped;
    char* swappedData = reinterpret_cast<char*>(&swapped);
    char* xData = reinterpret_cast<char*>(&x);

    swappedData[0] = xData[3];
    swappedData[1] = xData[2];
    swappedData[2] = xData[1];
    swappedData[3] = xData[0];

    return swapped;
  }

}