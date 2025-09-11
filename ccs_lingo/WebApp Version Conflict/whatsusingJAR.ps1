# Find the dependency path
mvn dependency:tree | Select-String "google-http-client-apache-v2" -Context 5,5

# Check if it's in your JAR
jar -tf target/ccs_lingo-2025.06.jar | Select-String "com/google/api/client/http/apache/v2"