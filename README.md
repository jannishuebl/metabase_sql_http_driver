# Metabase SQL-HTTP/DuckDB-HTTP Driver

The Metabase SQL-HTTP/DuckDB-HTTP driver allows [Metabase](https://www.metabase.com/) ([GitHub](https://github.com/metabase/metabase)) to send a Query via HTTP and consume the Result.

It can be used to query data from [DuckDB](https://duckdb.org/) ([GitHub](https://github.com/duckdb/duckdb)) via HTTP using e.g. [duckdb-http-api](https://github.com/jannishuebl/duckdb-http-api).

If you would like to open a GitHub issue to report a bug or request new features, or would like to open a pull requests against it, please do so in this repository, and not in the core Metabase GitHub repository.

## Obtaining the SQL-HTTP Metabase driver

### Where to find it

[Click here](https://github.com/jannishuebl/metabase_sql_http_driver/releases/latest) to view the latest release of the Metabase SQL-HTTP driver; click the link to download `sql-http.metabase-driver.jar`.

### How to Install it

Metabase will automatically make the SQL-HTTP driver available if it finds the driver in the Metabase plugins directory when it starts up.
All you need to do is create the directory `plugins` (if it's not already there), move the JAR you just downloaded into it, and restart Metabase.

By default, the plugins directory is called `plugins`, and lives in the same directory as the Metabase JAR.

For example, if you're running Metabase from a directory called `/app/`, you should move the SQL-HTTP driver to `/app/plugins/`:

```bash
# example directory structure for running Metabase with SQL-HTTP support
/app/metabase.jar
/app/plugins/sql-http.metabase-driver.jar
```

If you're running Metabase from the Mac App, the plugins directory defaults to `~/Library/Application Support/Metabase/Plugins/`:

```bash
# example directory structure for running Metabase Mac App with SQL-HTTP support
/Users/you/Library/Application Support/Metabase/Plugins/sql-http.metabase-driver.jar
```

If you are running the Docker image or you want to use another directory for plugins, you should specify a custom plugins directory by setting the environment variable `MB_PLUGINS_DIR`.

## Configuring

Once you've started up Metabase, go to add a database and select "SQL-HTTP". Provide the url to your server.

## Server Requirements

Your server must be able to consume a JSON in the format: 

```json
{
    "sql": "SELECT 1 as col1 where 1 = ?;",
    "params": [1]
}
```

and return a body in the jsonl-format, so one JSON-Object per ROW.

## How to build the SQL-HTTP .jar plugin yourself

1. Install VS Code with [DevContainer](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) extension (see [details](https://code.visualstudio.com/docs/devcontainers/containers))
2. Create some folder, let's say `sql_http_plugin`
3. Clone the `metabase_sql_http_driver` repository into `sql_http_plugin` folder
4. Copy `.devcontainer` from `sql_http_plugin/metabase_sql_http_driver` into `sql_http_plugin`
5. Clone the `metabase` repository of version you need into `sql_http_plugin` folder
6. Now content of the `sql_http_plugin` folder should looks like this:
```
  ..
  .devcontainer
  metabase
  metabase_sql_http_driver
```
7. Add sql_http record to the deps file `sql_http_plugin/metabase/modules/drivers/deps.edn`
The end of the file sholud looks like this:
```
  ...
  metabase/sqlserver          {:local/root "sqlserver"}
  metabase/vertica            {:local/root "vertica"}
  metabase/sql_http           {:local/root "sql_http"}}}  <- add this!
```
8. Create sql_http driver directory in the cloned metabase sourcecode:
```
> mkdir -p sql_http_plugin/metabase/modules/drivers/sql_http
```
9. Copy the `metabase_sql_http_driver` source code into created dir
```
> cp -rf sql_http_plugin/metabase_sql_http_driver/* sql_http_plugin/metabase/modules/drivers/sql_http/
```
10. Open `sql_http_plugin` folder in VSCode using DevContainer extension (vscode will offer to open this folder using devcontainer). Wait until all stuff will be loaded. At the end you will get the terminal opened directly in the VS Code, smth like this:
```
vscode ➜ /workspaces/sql_http_plugin $
```
11. Build the plugin
```
vscode ➜ /workspaces/sql_http_plugin $ cd metabase
vscode ➜ /workspaces/sql_http_plugin $ clojure -X:build:drivers:build/driver :driver :sql-http
```
12. jar file of SQL-HTTP plugin will be generated here sql_http_plugin/metabase/resources/modules/sql-http.metabase-driver.jar


## Acknowledgement

Thanks [@AlexR2D2](https://github.com/AlexR2D2) for originally authoring the duckdb connector, which was the startingpoint of this plugin
Thanks [@motherduckdb](https://github.com/motherduckdb) for authoring the duckdb connector, which was the startingpoint of this plugin
Thanks [@AhaMove](https://github.com/AhaMove/metabase-http-driver) for inspiration
