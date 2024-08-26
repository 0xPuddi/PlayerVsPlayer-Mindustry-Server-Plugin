dev:
	@./scripts/development.sh

gbuild:
	@./gradlew jar

# Restart java language server
grefresh:
	@./gradlew --refresh-dependencies

gcheck:
	@./gradlew check