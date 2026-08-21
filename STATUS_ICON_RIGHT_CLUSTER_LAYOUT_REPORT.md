# OS4 Right-Cluster Status-Icon Layout — Exact Positioning/Measurement Report

Decompiled OS4.0.0.15.XPMCNXM SystemUI
(`/Users/ink/developer/reverse/cache/systemui-9af08c49ea6e412e/jadx/sources/` + apktool res).
Purpose: make the module's LEFT clones pixel-identical to the RIGHT `statusIcons` cluster.

---

## 1. The right-cluster container

`apktool/res/layout/system_icons.xml` (whole file, 7 lines) — container is
**`com.android.systemui.statusbar.views.MiuiStatusIconContainer`**, id `@id/statusIcons`:

```xml
<com.android.systemui.statusbar.views.MiuiStatusBatteryContainer ... android:layout_width="match_parent" android:layout_height="match_parent">
    <com.android.systemui.statusbar.views.MiuiStatusIconContainer android:gravity="center_vertical" android:orientation="horizontal"
        android:id="@id/statusIcons" android:clipChildren="false"
        android:layout_width="match_parent" android:layout_height="match_parent" />
    <com.android.systemui.statusbar.views.MiuiHomePrivacyView ... android:layout_gravity="center" .../>
    <com.android.systemui.statusbar.views.MiuiBatteryMeterView ... android:layout_gravity="center" .../>
</com.android.systemui.statusbar.views.MiuiStatusBatteryContainer>
```

Chain in `status_bar.xml` (read fully):
`MiuiPhoneStatusBarView` (height=status_bar_height) → `status_bar_icons` FrameLayout →
`status_bar_contents` LinearLayout (`gravity=center_vertical`, `paddingTop/Bottom=0.0dp`) →
`MiuiNotificationStatusContainer` (line 19) → include `system_icons` (line 25, `paddingStart=4px` only)
→ `MiuiStatusBatteryContainer` → `statusIcons`.

`MiuiStatusBatteryContainer.onLayout` (`MiuiStatusBatteryContainer.java:50-130`) gives `statusIcons`
the full height: `top = (measuredHeight > H ? 0 : (H - measuredHeight)/2) + getPaddingTop()` with
child measured MATCH_PARENT ⇒ measuredHeight == H ⇒ **top = 0, box = full H** (lines 117-127).

The module's left host `phone_status_bar_left_container` is the same-depth sibling
(`status_bar.xml:10`: `wrap_content` × `match_parent`, **no gravity**) — same vertical span H.

---

## 2. How the container measures children — verbatim

`MiuiStatusIconContainer.java:966-1015` (`onMeasure`, condensed but literal):

```java
public final void onMeasure(int i, int i2) {
    this.measureViews.clear();
    int mode = View.MeasureSpec.getMode(i);
    int size = View.MeasureSpec.getSize(i);
    int childCount = getChildCount();
    int i3 = 0;
    for (int i4 = 0; i4 < childCount; i4++) {
        StatusIconDisplayable statusIconDisplayable = (StatusIconDisplayable) getChildAt(i4);
        if (statusIconDisplayable.isIconVisible() && !statusIconDisplayable.isIconBlocked()
                && !this.ignoredSlots.contains(statusIconDisplayable.getSlot())) {
            this.measureViews.add((View) statusIconDisplayable);
        }
    }
    ...
    int iMakeMeasureSpec = View.MeasureSpec.makeMeasureSpec(size, 0);   // width spec = UNSPECIFIED
    ...
    while (i6 < size2) {
        View view = (View) ((ArrayList) this.measureViews).get((size2 - i6) - 1);
        measureChild(view, iMakeMeasureSpec, i2);                        // height spec = passed through (EXACTLY H)
        int i8 = i6 == size2 + (-1) ? i3 : this.iconSpacing;
        ...
    }
    ...
    setMeasuredDimension(size, View.MeasureSpec.getSize(i2));            // container = full W × full H
}
```

- Child width spec: **UNSPECIFIED(size)** → child measures to intrinsic width (WRAP_CONTENT LP).
- Child height spec: parent's height spec (EXACTLY H) → `getChildMeasureSpec` with the child's fixed
  height LP (`mIconSize` = 20dp, §4) yields **EXACTLY(20dp)** → **measured height = 20dp always**.
- Per-slot horizontal spacing constant used here: `this.iconSpacing`
  (`reloadDimens$3`, lines 1090-1097): `R.dimen.status_bar_system_icon_spacing` = **0.0sp**.

## 3. How the container positions children vertically — verbatim (the key block)

`MiuiStatusIconContainer.java:283-292` (`onLayout`, FIRST loop — runs for ALL children, blocked or not):

```java
float paddingTop = (miuiStatusIconContainer2.getPaddingTop() + miuiStatusIconContainer2.getHeight()) / 2.0f;
int childCount = miuiStatusIconContainer2.getChildCount();
boolean z6 = false;
for (int i8 = 0; i8 < childCount; i8++) {
    View childAt = miuiStatusIconContainer2.getChildAt(i8);
    int measuredWidth  = childAt.getMeasuredWidth();
    int measuredHeight = childAt.getMeasuredHeight();
    int i9 = (int) (paddingTop - (measuredHeight / 2.0f));
    childAt.layout(0, i9, measuredWidth, measuredHeight + i9);   // x=0, y = centered on container center
}
```

⇒ **Every child box is laid out at x=0 and vertically CENTERED on the container center**
`(getPaddingTop() + getHeight())/2`. With `statusIcons` padding = 0 and full height H:
`boxTop = H/2 − measuredHeight/2 = H/2 − 10dp` (measuredHeight = 20dp).

Horizontal position is NOT done by layout(): the rest of `onLayout` (lines 311-590) walks children
right-to-left, computes each slot's **right edge** (`paddingEnd − (child paddingEnd + child paddingStart + child.getWidth())`,
line 364), decrements by `iconSpacing` (0) per slot (lines 577-579), stores it in the per-child
`NewStatusIconState` (tag set in `onViewAdded`, lines 1019-1047), and applies it with
`view.setTranslationX(...)`:
`MiuiStatusBarFolmeViewState.applyToView` (`anim/MiuiStatusBarFolmeViewState.java:152-183`,
line 170/183 `view.setTranslationX(f)`). Icons are right-aligned, abutting (spacing 0).

⇒ **Vertical placement is manual container math on the MEASURED height — it does NOT use the child's
layoutParams height, and it ignores gravity.** A child dropped into a plain `LinearLayout` gets
NONE of this automatic centering.

## 4. What layoutParams the holders are given — verbatim

`statusbar/phone/ui/IconManager.java`:

- ctor (line 81): `this.mIconSize = context.getResources().getDimensionPixelSize(R.dimen.status_bar_icon_height);` → **20.0dp**.
- `onCreateLayoutParams(StatusBarIcon.Shape)` (lines 223-225):

```java
public LinearLayout.LayoutParams onCreateLayoutParams(StatusBarIcon.Shape shape) {
    return new LinearLayout.LayoutParams(shape == StatusBarIcon.Shape.FIXED_SPACE ? this.mIconSize : -2, this.mIconSize);
}
```

⇒ **width = WRAP_CONTENT (-2)** for normal slots, **width = 20dp fixed** for `FIXED_SPACE`;
**height = 20dp fixed for EVERY slot — never MATCH_PARENT**.
- `addHolder` type 0 (lines 93-103): `new StatusBarIconView(ctx, slot, null, blocked)` +
  `setAdjustViewBounds(true)` + `newAnimationFeature=true` + `set(statusBarIcon)` +
  `mGroup.addView(view, index, onCreateLayoutParams(statusBarIcon.shape))`.
- `onSetIcon` (lines 308-316) re-derives LPs and swaps when w/h differ.
- `onDensityOrFontScaleChanged` (lines 253-265) resets every child to `new LinearLayout.LayoutParams(-2, mIconSize)`.

`statusbar/phone/ui/DarkIconManager.java:38-43` (`onCreateLayoutParams` override):

```java
public final LinearLayout.LayoutParams onCreateLayoutParams(StatusBarIcon.Shape shape) {
    LinearLayout.LayoutParams layoutParamsOnCreateLayoutParams = super.onCreateLayoutParams(shape);
    int i = this.mIconHorizontalMargin;
    layoutParamsOnCreateLayoutParams.setMargins(i, 0, i, 0);
    return layoutParamsOnCreateLayoutParams;
}
```

`mIconHorizontalMargin` = `R.dimen.status_bar_icon_horizontal_margin`
(`dagger/DaggerReferenceGlobalRootComponent.java:13852`) = **0.0sp** (values/dimens.xml:6344;
1.0sp only on `sw720dp`). So slot margins = **0 / 0 / 0 / 0** on this device, and `LinearLayout.LayoutParams.gravity` is never touched ⇒ stays **-1 (NO_GRAVITY)**.

Dimen values (`apktool/res/values/dimens.xml`): `status_bar_icon_height` 20.0dp (6343),
`status_bar_icon_size` 13.0dp (6347), `status_bar_icon_size_sp` 13.0sp (6348),
`status_bar_icon_drawing_size(_dark)` = status_bar_icon_size (6341-6342),
`status_bar_icon_scale_factor` 1.0 (6346), `status_bar_system_icon_spacing` 0.0sp (6399),
`status_bar_padding_top/bottom` 0.0dp (6387/6391).

## 5. Where the glyph paints inside the view — verbatim

`statusbar/StatusBarIconView.java`:

- ctor (line 203): `setScaleType(ImageView.ScaleType.CENTER);` and (line 212) `setCropToPadding(true);`
  (FIXED_SPACE icons switch to `FIT_CENTER` in `set()`, lines 714-716).
- `onDraw` (lines 504-541):

```java
if (this.mIconAppearAmount > 0.0f) {
    canvas.save();
    int width = getWidth() / 2;
    int height = getHeight() / 2;
    float f = this.mIconScale;
    float f2 = this.mIconAppearAmount;
    canvas.scale(f * f2, f * f2, width, height);   // scale pivot = BOX CENTER
    super.onDraw(canvas);
    canvas.restore();
}
// dot state paints circles at (getWidth()/2, getHeight()/2)
```

`super.onDraw` = ImageView with `ScaleType.CENTER` ⇒ drawable painted at its intrinsic size,
translated by `round((viewW−drawableW)/2), round((viewH−drawableH)/2)` — i.e. **glyph center =
view-box center**, clipped equally if the intrinsic exceeds the 20dp box.
- `mIconScale` (`maybeUpdateIconScaleDimens`, lines 448-471, 469):
  `(mStatusBarIconDrawingSize / mOriginalStatusBarIconSize) × fMin × mScaleToFitNewIconSize`
  with both = `status_bar_icon_size` 13dp ⇒ 1.0, and `fMin = 1.0` when LP width is WRAP_CONTENT
  (lines 458-467 skip the fit clamp). Glyph scale = 1.0 (canvas scale ~identity).
- `onMeasure` (lines 578-584): `super.onMeasure` (ImageView — height EXACTLY ⇒ 20dp wins; width
  UNSPECIFIED ⇒ intrinsic + 0 padding), then for status icons width ×= `mScaleToFitNewIconSize`
  (= 1.0f). No intrinsic-height override.

⇒ **Measured box = (drawable intrinsic width, 20dp). Glyph is painted centered on the box center.
Vertical glyph position = container center = H/2** (because the container centers the 20dp box).

---

## 6. Answers

1. **Measured box of a right-cluster icon child**: width = the drawable's intrinsic width
   (each `stat_sys_*` glyph, measured UNSPECIFIED, no padding), **height = 20dp fixed**
   (`status_bar_icon_height`, EXACTLY via the holder's fixed LP height). Not match_parent, not intrinsic-height.
2. **Vertical position in the status bar**: the 20dp box is **manually centered by
   `MiuiStatusIconContainer.onLayout`** — `top = (paddingTop + H)/2 − 10dp`; glyph (painted at box
   center) ends up at **H/2** of the status-bar content height. (Horizontal: right-aligned slots,
   every slot's right edge placed at the previous right edge − `iconSpacing`(0); applied via
   `setTranslationX`, not layout.)
3. **Where the glyph paints in its own bounds**: **centered on the box center** — `ScaleType.CENTER`
   (ctor line 203) + `onDraw` canvas scale about `(w/2, h/2)` (line 513). Clipped top/bottom equally
   if the intrinsic glyph is taller than the 20dp box. Never top-left, never top-aligned.
4. **Child layoutParams**: `LinearLayout.LayoutParams(width=−2 (WRAP_CONTENT), height=20dp)`
   (width = 20dp only for `FIXED_SPACE`), margins = `(0,0,0,0)` `mIconHorizontalMargin`,
   gravity = −1 (NO_GRAVITY), from `IconManager.onCreateLayoutParams` (lines 223-225) +
   `DarkIconManager` margins (lines 38-43). `onSetIcon`/`onDensityOrFontScaleChanged` keep these.
5. **Container spacing between slots**: `iconSpacing` = `status_bar_system_icon_spacing` = **0** and
   per-view margins 0 ⇒ slots abut; the container itself adds no padding (statusIcons XML has none;
   `paddingTop/Bottom=0`).

### The "raised" clone — root cause and fix

The module's left container (`LeftContainerHooker.ensureContainer`) builds a fresh
`LinearLayout` and sets:

```kotlin
left.orientation = LinearLayout.HORIZONTAL
left.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT)
    .apply { gravity = Gravity.CENTER_VERTICAL }   // gravity of the container IN ITS PARENT — a no-op (fills the host)
```

but **never sets the LinearLayout's own `gravity`**. A LinearLayout's own `gravity` defaults to
`START | TOP`, so a horizontal LinearLayout **top-aligns its children**. The clones keep the copied
source LPs (20dp tall), so each clone box sits at top=0 ⇒ **clone glyph center = 10dp from the top**,
while the right cluster's glyph center = **H/2**. The clone is raised by **H/2 − 10dp**
(e.g. H=24dp → 2dp; H=30dp → 5dp; H=40dp → 10dp — visibly raised).

Fixes (any one):
- `left.gravity = android.view.Gravity.CENTER_VERTICAL` on the LinearLayout object (the important
  one — the module currently only sets the layoutParams gravity), or
- per-clone `layoutParams.gravity = Gravity.CENTER_VERTICAL`.

Then the 20dp clone box is centered exactly like the real container does manually
`(top = (H−20dp)/2)` ⇒ glyph center = H/2 ⇒ pixel-identical vertically.

Do **not** change the copied height to MATCH_PARENT "because the source looks taller": the source LP
height is genuinely 20dp; MATCH_PARENT would coincidentally also center the glyph (box fills H) but
would diverge from `onSetIcon`'s own re-derivation and is not what the system uses. A MATCH_PARENT
height would NOT cause the raised look — the raised look is caused by the missing vertical gravity
(top-aligned 20dp box), i.e. exactly the opposite of the hypothesis in the task: the source is
positioned via manual onLayout with its measured 20dp height, and the clone must be **centered**, not
height-matched.

Horizontal: with copied margins (0) and container spacing 0 the left row abuts just like the right
cluster (right cluster is right-aligned / mirrored order — that's a design choice, not a pixel bug).