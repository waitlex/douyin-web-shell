# 壳工程没有反射与序列化，保持默认即可。
# 若后续接入 JSBridge，保留被 @JavascriptInterface 标注的类：
# -keepclassmembers class * {
#     @android.webkit.JavascriptInterface <methods>;
# }
