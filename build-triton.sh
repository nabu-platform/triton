# build jre
echo "Building JRE"
bash build-jre.sh

# clean the lib folder
echo "Clean lib folder"
rm -rf /home/alex/files/triton/lib/

# copy all the necessary dependencies to that folder
echo "Copying dependencies to lib folder"
mvn install dependency:copy-dependencies -DoutputDirectory=/home/alex/files/triton/lib/

# build project
echo "Building project"
mvn package

echo "Copy project to lib folder"
# copy jar to lib
cp target/triton-*.jar /home/alex/files/triton/lib/

echo "Package the Triton Server"
# package the server
/home/alex/files/apps/jdk-14.0.2/bin/jpackage --input /home/alex/files/triton/lib \
	--name "triton-server" \
	--java-options "-Xmx256m" \
	--main-jar triton-1.0-SNAPSHOT.jar \
	--main-class "be.nabu.libs.triton.Main" \
	--runtime-image /home/alex/files/triton/jre \
	--icon /home/alex/files/triton/logo.png \
	--app-version 0.0.1 \
	--vendor "Celerium" \
	--type deb

echo "Package the Triton Client"
# package the client
/home/alex/files/apps/jdk-14.0.2/bin/jpackage --input /home/alex/files/triton/lib \
	--name "triton-client" \
	--java-options "-Xmx256m" \
	--main-jar triton-1.0-SNAPSHOT.jar \
	--main-class "be.nabu.libs.triton.TritonShell" \
	--runtime-image /home/alex/files/triton/jre \
	--icon /home/alex/files/triton/logo.png \
	--app-version 0.0.1 \
	--vendor "Celerium" \
	--type deb
	

