Build the mvncloner app
Create a DockerFile for the mvncloner app.
Create a docker compose file for this app which includes a container for this app and a container for sonatype nexus.
Make the source params configurable externally. Get the values from the environment variables as set by env.sh. Make sure not to commit env.sh but put it in .gitignore.
Configure the destination params to match the params needed to upload the libraries to nexus.
run the docker compose file and verify that the contents on the remote mavn repository are mirrored to the nexus repository.

