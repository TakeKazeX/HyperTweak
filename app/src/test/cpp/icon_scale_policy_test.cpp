// SPDX-License-Identifier: Apache-2.0
#include "icon_scale_policy.h"
#include <cassert>
#include <limits>
#include <initializer_list>
using namespace hypertweak::native;
int main() {
 const double width=94.39330694594933,current=.68,size=width*current,padding=9.663665;
 const auto maximum=DesktopIconMaximum(current,width,size,padding);
 assert(maximum==kDesktopIconAbsoluteMaximum);
 const auto enlarged=BoundDesktopIconRequest(1.02f,.54f,.72f,maximum);
 assert(enlarged.requested==1.02f&&enlarged.minimum==.01f&&enlarged.maximum>=enlarged.requested);
 for (const int percent : {125,150,175,200}) {
  const float requested=static_cast<float>(current*percent/100);
  const auto result=BoundDesktopIconRequest(requested,.54f,.72f,maximum);
  assert(result.requested==requested && result.maximum>=requested);
  assert(DesktopIconMaximum(requested,width,width*requested,-10)==maximum);
 }
 const auto smaller=BoundDesktopIconRequest(.34f,.54f,.72f,maximum);
 assert(smaller.requested==.34f&&smaller.minimum<=smaller.requested);
 // A rebuilt provider's scale cannot prove geometry until its immutable size agrees.
 assert(DesktopIconMaximum(.842,width,width*.72,padding)==0);
 assert(DesktopIconMaximum(current,0,size,padding)==0);
 assert(DesktopIconMaximum(current,width,size,-1)==maximum);
 assert(DesktopIconMaximum(current,width,size,std::numeric_limits<double>::quiet_NaN())==0);
 assert(DesktopIconMaximum(std::numeric_limits<double>::quiet_NaN(),width,size,padding)==0);
 const auto unavailable=BoundDesktopIconRequest(.34f,.54f,.72f,0);
 assert(unavailable.requested==.34f&&unavailable.minimum==.54f&&unavailable.maximum==.72f);
 const auto invalid=BoundDesktopIconRequest(.34f,.72f,.54f,maximum);
 assert(invalid.minimum==.72f&&invalid.maximum==.54f);
 assert(ValidIconPercent(-1)&&ValidIconPercent(50)&&ValidIconPercent(150)&&ValidIconPercent(175)&&ValidIconPercent(200)&&!ValidIconPercent(49)&&!ValidIconPercent(201));
 assert(EffectiveIconPercent(100)==100&&EffectiveIconPercent(150)==150&&EffectiveIconPercent(200)==150&&EffectiveIconPercent(-1)==-1);
}
