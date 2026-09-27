package com.takekazex.hypertweak.hook.rules.camera
import org.jf.dexlib2.iface.reference.MethodReference
/** Optional integration check against an external, hash-identified host APK; no target code runs. */
class CameraSemanticApkTest {
@org.junit.Test fun `current camera semantic contracts resolve without a version name table`() {
 val apk = System.getenv("CAMERA_APK")
 org.junit.Assume.assumeTrue("Set CAMERA_APK to run host artifact verification", apk != null)
 val digest = java.security.MessageDigest.getInstance("SHA-256")
 java.io.File(apk!!).inputStream().use { stream ->
  val buffer = ByteArray(65536)
  while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
 }
 org.junit.Assert.assertEquals("1cec1ff2203d9d1c358b7ea24f6db5b62adeb5d3f6ceca48b0fa1df932d685d9",
  digest.digest().joinToString("") { "%02x".format(it) })
 val start = System.nanoTime()
 val dex = CameraDexIndex.open(apk)
 fun refs(key: String, methods: List<MethodReference>, expected: Int = 1) {
  val values = methods.distinctBy(CameraDexIndex::descriptor)
  println("$key (${values.size}/$expected): ${values.map(CameraDexIndex::descriptor)}")
  check(values.size == expected) { key }
 }
 val lcc = dex.strings("LCC").single { it.returnType == "Z" && it.parameterTypes.isEmpty() }
 val facade = lcc.definingClass
 val config = dex.classDefinition(facade)!!.fields.single { f ->
  dex.declared(f.type).count { it.returnType == "Z" && it.parameterTypes.isEmpty() } > 20
 }.type
 println("facade=$facade config=$config")
 refs("LCC", listOf(lcc))
 refs("dynamic DDF", dex.strings("dynamic_ddfid: ").flatMap { dex.code(it).calls.map { call -> call.method } }
  .filter { CameraDexIndex.isInstanceGetter(it, config, "I") })
 for ((entry, id) in listOf("masterlive/MasterLiveModuleEntry" to 231)) {
  val getter = dex.declared("Lcom/android/camera/features/mode/$entry;").single { it.name == "getModuleId" }
  check(dex.hasNumber(getter, id.toLong())) { "$entry must identify mode $id" }
 }

 refs("brand", dex.declared(facade).filter { it.returnType == "Ljava/lang/String;" &&
  dex.code(it).arrays.any { read -> read.index == CameraDexIndex.Value.Number(0) &&
   (read.producer as? CameraDexIndex.Value.Call)?.method?.returnType == "[Ljava/lang/String;" } })
 refs("mismatch", dex.declared(facade).filter { it.returnType == "Z" && it.parameterTypes.isEmpty() && it.accessFlags and 8 != 0 &&
  dex.code(it).calls.any { call -> call.method.returnType == config } })
 refs("effect table", dex.ownerStrings("ComponentRunningMasterLive", "pref_master_live_key").flatMap(dex::declared)
  .flatMap { dex.code(it).calls }.filter { it.method.definingClass == "Ljava/util/Collections;" && it.method.name == "unmodifiableMap" }
  .flatMap { it.arguments }.filterIsInstance<CameraDexIndex.Value.Call>().map { it.method }.filter { CameraDexIndex.isInstanceGetter(it,config,"Ljava/util/Map;") })
 refs("mode order", dex.strings("pref_camera_sort_modes_key").filter { it.returnType == "[I" }.flatMap { dex.localCalls(it) }.filter { CameraDexIndex.isInstanceGetter(it,config,"[I") }, 2)
 for (key in listOf("pref_ai_aperture_key", "pref_beautify_nevus_wipe_switch", "pref_beautify_makeup_male_switch", "pref_photo_selfie_setting")) {
  refs(key, dex.preferenceGates(key).filter { it.parameterTypes.isEmpty() })
 }
 refs("smart composition", dex.preferenceGates("pref_camera_crop_preferred_key").filter { it.definingClass == config })
 refs("adaptive lens", dex.preferenceGates("pref_camera_auto_fallback").filter { it.parameterTypes.size == 1 },2)
 check(dex.preferenceFields("pref_cai_type_key").size == 1)
 for(key in listOf("pref_camera_crop_preferred_key", "pref_front_mirror_boolean_key")) {
  println("resources $key: ${dex.preferenceResources(key)}")
  check(dex.preferenceResources(key).size == 2)
 }
 for ((key,anchors,result,params) in listOf(
  listOf("watermark renderer", "deviceLogo", "V", "Ljava/lang/String;Ljava/lang/String;Z"),
  listOf("watermark formatter", "modelFormat|@{logo}", "V", "Ljava/lang/String;Ljava/lang/String;ZZ"),
  listOf("watermark filter", "filterData: E |filterData: delete ", "V", "Z"),
  listOf("watermark scan", "initData: E|initData: X", "V", ""),
  listOf("watermark path", "workingPath", "Ljava/nio/file/Path;", ""),
  listOf("watermark debug", "camera.cloud.watermark.debug", "Ljava/lang/Object;", ""),
  listOf("launch classification", "CAPTURE|STREET", "Ljava/lang/String;", ""),
  listOf("guide", "pref_camera_global_guide_shown_key", "Z", ""),
  listOf("shutter selector", "key_shutter_sound", "I", ""),
  listOf("sort order", "pref_camera_sort_modes_key", "[I", "Lv2/U;"),
  listOf("persisted support", "all_support_mode_list", "Z", "I")
 )) refs(key, dex.strings(*anchors.split('|').toTypedArray()).filter { it.returnType == result && it.parameterTypes.joinToString("") == params && (key != "guide" || dex.hasNumber(it,2)) })
 val quality = dex.strings("pref_camera_jpegquality_key").filter { ref -> dex.classDefinition(ref.returnType)?.accessFlags?.and(0x4000) != 0 && dex.classDefinition(ref.returnType) != null }
 refs("quality capability", quality.flatMap { dex.code(it).calls }.map { it.method }.filter { CameraDexIndex.isInstanceGetter(it,config,"Z") })
 refs("Leica style/sounds",dex.strings("leica_default","leica_mechanical").flatMap { dex.code(it).calls }.map { it.method }.filter { CameraDexIndex.isInstanceGetter(it,config,"Z") })
 val componentOwners = dex.ownerStrings("ComponentRunningMasterLive", "pref_master_live_key")
 val selected = dex.methods.filter { ref -> ref.accessFlags and 8 != 0 && ref.returnType == "Ljava/lang/String;" && ref.parameterTypes.map(CharSequence::toString) == listOf("I") &&
  dex.code(ref).calls.any { it.method.definingClass in componentOwners && it.method.name == "getComponentValue" } }
 refs("MasterLive selected effect",selected)
 refs("MasterLive slow-motion type",dex.strings("1").filter { ref -> ref.accessFlags and 8 != 0 && ref.returnType == "Z" && ref.parameterTypes.map(CharSequence::toString) == listOf("I") &&
  dex.code(ref).calls.any { CameraDexIndex.descriptor(it.method) == CameraDexIndex.descriptor(selected.single()) } })
 val sources = dex.methods.filter { ref -> ref.accessFlags and 8 != 0 && ref.returnType == "Ljava/lang/Class;" && ref.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
  dex.code(ref).calls.any { it.method.definingClass == "Ljava/lang/Class;" && it.method.name == "forName" } &&
  dex.code(ref).calls.any { it.method.definingClass == "Ljava/lang/String;" && it.method.name == "hashCode" } &&
  dex.code(ref).calls.any { it.method.definingClass in listOf("Ljava/util/Map;", "Ljava/util/HashMap;") && it.method.name == "get" } &&
  dex.code(ref).calls.any { it.method.definingClass == "Ljava/lang/Integer;" && it.method.name == "valueOf" } &&
  dex.code(ref).calls.any { it.method.returnType == "Ljava/lang/String;" && it.method.parameterTypes.map(CharSequence::toString) == listOf("I", "Ljava/lang/String;") }
 }
 refs("source-name factory", sources)
 refs("smart composition version",dex.strings("SupportSmartCompositionVersion:").filter { it.returnType == "Z" && it.parameterTypes.size==1 })
 val size = dex.strings("getLivePhotoVideoSize: fail").filter { it.returnType == "Landroid/util/Size;" && it.parameterTypes.size==2 }
 refs("live-photo size",size)
 val modeFields = dex.code(size.single()).reads.filter { it.definingClass == size.single().parameterTypes[1].toString() && it.type == "I" }.distinctBy { it.toString() }
 println("size module field: $modeFields")
 check(modeFields.size==1)
 println("Elapsed seconds: ${(System.nanoTime()-start)/1e9}")
}
}
