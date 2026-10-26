-dontobfuscate

# Paho loads its network modules and logger reflectively.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
