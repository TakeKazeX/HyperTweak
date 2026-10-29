// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <algorithm>
#include <cmath>
namespace hypertweak::native {
inline bool ValidIconPercent(int value) { return value==-1 || (value>=50 && value<=150); }
inline double DesktopIconMaximum(double current,double width,double size,double padding) {
 if (!std::isfinite(width) || width<=4 || !std::isfinite(size) || size<=0 ||
     !std::isfinite(padding) || padding<0 || !std::isfinite(current) || current<=0 || current>4 ||
     std::abs(size-width*current)>0.1) return 0;
 // The host already subtracted title lines and margins from its native padding.
 const double maximum=std::max(current,std::min(width-4.0,size+2*padding-4.0)/width);
 return std::isfinite(maximum)&&maximum>0&&maximum<=4 ? maximum : 0;
}
struct DesktopIconRequest {float requested,minimum,maximum;};
inline DesktopIconRequest BoundDesktopIconRequest(float requested,float minimum,float maximum,double available) {
 DesktopIconRequest result{requested,minimum,maximum};
 if (!std::isfinite(requested) || requested<0.01f || requested>4 || !std::isfinite(available) || available<=0 || available>4 ||
     !std::isfinite(minimum) || !std::isfinite(maximum) || minimum>maximum) return result;
 return {std::min(requested,static_cast<float>(available)),std::min(minimum,0.01f),std::max(maximum,static_cast<float>(available))};
}
}
