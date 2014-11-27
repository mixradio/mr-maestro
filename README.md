# Exploud - exploding things onto the cloud

## Intro

Exploud is the main port of call for deploying applications to AWS. It will kick of each stage of the deployment and track any resulting task which describes the progress of the operation.

## Resources

`GET /ping`
`pong`.

`GET /healthcheck`
Shows the health of the application.

`GET /1.x/instances/:app-name`
All instances of an application in JSON form.

`GET /1.x/images/:app-name`
All images of an application in JSON form.

`GET /1.x/deployments`
Query deployments. Query parameters allowed are:

  * `application` - the application to filter by
  * `environment` - the environment to filter by
  * `start-from` - the lower bound of a date filter on deployment start time.
  * `start-to` - the upper bound of a date filter on deployment start time.
  * `size` - the number of deployments to retrieve (default: 10)
  * `from` - the number of deployments to start retrieving from (default: 0)

`GET /1.x/deployments/:deployment-id`
The details of a specific deployment.

`GET /1.x/applications`
The list of all applications known about by Lister.

`GET /1.x/applications/:application`
The details of a specific application.

`PUT /1.x/applications/:application`
Upsert an application to Lister, Pedantic and Tyrant.

`POST /1.x/applications/:application/deploy`
Begin the deployment of an application. JSON body must include the following:

  * `ami` - the image to use for deployment
  * `environment` - the environment to deploy to
  * `message` - a message which describes why the deployment is happening
  * `user` - the user who is making the deployment

`POST /1.x/applications/:application/rollback`
Begin a rollback of an application (a rollback is considered to be a deployment of the penultimate completed deployment for the application and environment). JSON body must include the following:

  * `environment` - the environment to deploy to
  * `message` - a message which describes why the deployment is happening
  * `user` - the user who is making the deployment

`GET /1.x/environments`
All environments in JSON form.

`GET /1.x/tasks`
A text representation of the tasks the system is currently tracking.
