# smbj (SMB shares). Optional features Candle does not use reference APIs Android lacks:
# Kerberos login (org.ietf.jgss) and mbassador's expression filters (javax.el).
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**

# smbj's event bus (mbassador) finds its listeners by annotation, through reflection.
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler *;
}
-keep class net.engio.mbassy.** { *; }
