// SPDX-License-Identifier: Apache-2.0
#include "icon_scale_policy.h"
#include <cassert>
#include <limits>
using namespace hypertweak::native;
int main() {
 const double width=94.39330694594933,current=.68,size=width*current,padding=9.663665;
 const auto maximum=DesktopIconMaximum(current,width,size,padding);
 assert(maximum>=current&&maximum*width<=size+2*padding-4+.000001);
 const auto enlarged=BoundDesktopIconRequest(1.02f,.54f,.72f,maximum);
 assert(std::abs(enlarged.requested-maximum)<.000001&&enlarged.minimum==.01f&&enlarged.maximum>=enlarged.requested);
 const auto smaller=BoundDesktopIconRequest(.34f,.54f,.72f,maximum);
 assert(smaller.requested==.34f&&smaller.minimum<=smaller.requested);
 // A rebuilt provider's scale cannot prove geometry until its immutable size agrees.
 assert(DesktopIconMaximum(.842,width,width*.72,padding)==0);
 assert(DesktopIconMaximum(current,0,size,padding)==0);
 assert(DesktopIconMaximum(current,width,size,-1)==0);
 assert(DesktopIconMaximum(std::numeric_limits<double>::quiet_NaN(),width,size,padding)==0);
 const auto unavailable=BoundDesktopIconRequest(.34f,.54f,.72f,0);
 assert(unavailable.requested==.34f&&unavailable.minimum==.54f&&unavailable.maximum==.72f);
 const auto invalid=BoundDesktopIconRequest(.34f,.72f,.54f,maximum);
 assert(invalid.minimum==.72f&&invalid.maximum==.54f);
 assert(ValidIconPercent(-1)&&ValidIconPercent(50)&&ValidIconPercent(150)&&!ValidIconPercent(49)&&!ValidIconPercent(151));
}
