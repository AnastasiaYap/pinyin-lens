# The tile service and the process-text activity are only ever instantiated by
# the system, via the manifest — keep them addressable by name.
-keep class io.tr8.pinyinlens.toggle.PinyinTileService { *; }
-keep class io.tr8.pinyinlens.toggle.ToggleReceiver { *; }
-keep class io.tr8.pinyinlens.ui.ProcessTextActivity { *; }

# RubyTextView is inflated from XML.
-keep class io.tr8.pinyinlens.ui.RubyTextView { public <init>(...); }

# The accessibility service is instantiated by the system from the manifest.
-keep class io.tr8.pinyinlens.overlay.PinyinAccessibilityService { *; }
