// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <algorithm>
#include <cmath>
namespace hypertweak::native {
// Absolute provider limit; the user setting separately limits relative size to 200%.
constexpr double kDesktopIconAbsoluteMaximum = 4.0;
inline bool ValidIconPercent(int value) { return value==-1 || (value>=50 && value<=200); }
inline double DesktopIconMaximum(double current,double width,double size,double padding) {
 if (!std::isfinite(width) || width<=4 || !std::isfinite(size) || size<=0 ||
     !std::isfinite(padding) || !std::isfinite(current) || current<=0 || current>4 ||
     std::abs(size-width*current)>0.1) return 0;
 // Cell padding is a layout result, not an upper bound for an explicit override.
 // Keep validating the active geometry, but let the provider apply the requested size.
 // Padding can become negative when an enlarged icon exceeds the stock cell budget.
 return kDesktopIconAbsoluteMaximum;
}
struct DesktopIconRequest {float requested,minimum,maximum;};
inline DesktopIconRequest BoundDesktopIconRequest(float requested,float minimum,float maximum,double available) {
 DesktopIconRequest result{requested,minimum,maximum};
 if (!std::isfinite(requested) || requested<0.01f || requested>4 || !std::isfinite(available) || available<=0 || available>4 ||
     !std::isfinite(minimum) || !std::isfinite(maximum) || minimum>maximum) return result;
 return {std::min(requested,static_cast<float>(available)),std::min(minimum,0.01f),std::max(maximum,static_cast<float>(available))};
}
}
