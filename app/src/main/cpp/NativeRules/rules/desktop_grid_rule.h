// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>
namespace hypertweak::native {
// 0 follows the host; 1..12 encode (4..6 columns) x (5..8 rows).
inline bool ValidDesktopGrid(int code){return code>=0&&code<=12;}
inline int DesktopGridCode(int x,int y){return x>=4&&x<=6&&y>=5&&y<=8?1+(x-4)*4+y-5:0;}
inline int DesktopGridColumns(int code){return code?4+(code-1)/4:0;}
inline int DesktopGridRows(int code){return code?5+(code-1)%4:0;}
bool DesktopGridGeometryActive();
bool DesktopGridNeedsPreparation();
void SetDesktopGrid(int code);
bool PrepareDesktopGridLayout(void* handle);
bool ApplyDesktopGridRule(void* handle);
void PrepareDesktopGridStorage();
void ResetDesktopGridAfterFork();
const char* DesktopGridReason();
struct DesktopGridDispatch {uintptr_t target=0,x1=0,x2=0,x3=0,x5=0,x4=0;};
uintptr_t DesktopGridFrame(uintptr_t thread,uintptr_t heap,uintptr_t null,uintptr_t pool,uintptr_t grid,DesktopGridDispatch*);
int DesktopGridStage();
int DesktopGridObserved();
int DesktopGridRequested();
int DesktopGridReadbackDelay();
}
