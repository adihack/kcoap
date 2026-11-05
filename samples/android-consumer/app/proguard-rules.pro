# Intentionally empty. The release build shrinks kcoap and its dependencies with only the rules their own
# jars bring (META-INF/proguard and META-INF/com.android.tools), as in any app that uses kcoap. If R8 needs
# a rule for kcoap, it goes into the kcoap module that needs it, not here.
