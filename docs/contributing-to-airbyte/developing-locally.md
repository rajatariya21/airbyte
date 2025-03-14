# Developing Locally

The following technologies are required to build Airbyte locally.

1. `Java 14`
2. `Node 14`
3. `Python 3.7`
4. `Docker`
5. `Postgresql`
6. `Jq`
7. `CMake`

{% hint style="info" %}
Manually switching between different language versions can get hairy. We recommend using a version manager such as `pyenv` or `jenv`.
{% endhint %}

To start contributing:

1. Start by [forking](https://docs.github.com/en/github/getting-started-with-github/fork-a-repo) the repository
2. Clone the fork on your workstation:

   ```bash
   git clone git@github.com:{YOUR_USERNAME}/airbyte.git
   cd airbyte
   ```

3. You're ready to start!

## Build with `gradle`

To compile the code and run unit tests:

```bash
./gradlew clean build
```

This will build all the code and run all the unit tests.

`./gradlew build` creates all the necessary artifacts \(Webapp, Jars and Docker images\) so that you can run Airbyte locally. Since this builds everything, it can take some time.

To compile and build just the core systems:

```bash
CORE_ONLY=1 ./gradlew build
```

{% hint style="info" %}
Gradle will use all CPU cores by default. If Gradle uses too much/too little CPU, tuning the number of CPU cores it uses to better suit a dev's need can help.

Adjust this by either, 1. Setting an env var: `export GRADLE_OPTS="-Dorg.gradle.workers.max=3"`. 2. Setting a cli option: `./gradlew build --max-workers 3` 3. Setting the `org.gradle.workers.max` property in the `gradle.properties` file.

A good rule of thumb is to set this to \(\# of cores - 1\).
{% endhint %}

{% hint style="info" %}
On Mac, if you run into an error while compiling openssl \(this happens when running pip install\), you may need to explicitly add these flags to your bash profile so that the C compiler can find the appropriate libraries.

```text
export LDFLAGS="-L/usr/local/opt/openssl/lib"
export CPPFLAGS="-I/usr/local/opt/openssl/include"
```
{% endhint %}

## Run in `dev` mode with `docker-compose`

```bash
CORE_ONLY=1 ./gradlew build
VERSION=dev docker-compose up
```

The build will take a few minutes. Once it completes, Airbyte compiled at current git revision will be running in `dev` mode in your environment.

In `dev` mode, all data will be persisted in `/tmp/dev_root`.

## Run acceptance tests

To run acceptance \(end-to-end\) tests, you must have the Airbyte running locally.

```bash
CORE_ONLY=1 ./gradlew build
VERSION=dev docker-compose up
./gradlew :airbyte-tests:acceptanceTests
```

### Develop on `airbyte-webapp`

* Spin up Airbyte locally so the UI can make requests against the local API.
* Stop the `webapp`.

```bash
docker-compose stop webapp
```

* Start up the react app.

```bash
cd airbyte-webapp
npm install
npm start
```

* Happy Hacking!

### Connector Specification Caching

The Configuration API caches connector specifications. This is done to avoid needing to run docker everytime one is needed in the UI. Without this caching, the UI crawls. If you update the specification of a connector and you need to clear this cache so the API / UI pick up the change. You have two options: 1. Go to the Admin page in the UI and update the version of the connector. Updating to the same version will for the cache to clear for that connector. 1. Restart the server

```bash
VERSION=dev docker-compose down -v
VERSION=dev docker-compose up
```

### Resetting the Airbyte developer environment

Sometimes you'll want to reset the data in your local environment. One common case for this is if you are updating an connector's entry in the database \(`airbyte-config/init/src/main/resources/config`\), often the easiest thing to do, is wipe the local database and start it from scratch. To reset your data back to a clean install of Airbyte, follow these steps:

* Delete the datastore volumes in docker

  ```bash
    VERSION=dev docker-compose down -v
  ```

* Remove the data on disk

  ```bash
    rm -rf /tmp/dev_root
    rm -rf /tmp/airbyte_local
  ```

* Rebuild the project

  ```bash
   CORE_ONLY=1 ./gradlew build
   VERSION=dev docker-compose up -V
  ```

## Troubleshooting

### `gradlew Could not target platform: 'Java SE 14' using tool chain: 'JDK 8 (1.8)'.`

Somehow gradle didn't pick up the right java version for some reason. Find the install version and set the `JAVA_HOME` environment to point to the JDK folder.

For example:

```text
env JAVA_HOME=/usr/lib/jvm/java-14-openjdk ./gradlew  :airbyte-integrations:connectors:your-connector-dir:build
```

