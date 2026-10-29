# Paper NBT roundtrip probe

Build with Java 25 and Gradle, install the resulting JAR temporarily on a Paper test server, and inspect the log for the three `NBT_ROUNDTRIP_OK` messages. Remove the JAR and restart the server afterwards. The probe creates an enchanted book, a potion, and a shulker box containing both, and checks Paper serialization and nested contents. It does not test live player inventory persistence or auction transactions.
