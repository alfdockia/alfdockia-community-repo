<#if error??>
{
  "error": {
    "statusCode": ${error.statusCode?c},
    "code": "${error.code?js_string}",
    "message": "${error.message?js_string}"
  }
}
<#else>
{
  "data": {
    "agentId": "${data.agentId?js_string}",
    "name": "${data.name?js_string}",
    "desiredState": "${data.desiredState?js_string}",
    "currentState": "${data.currentState?js_string}",
    "statusUrl": "${data.statusUrl?js_string}"
  },
  "location": "${location?js_string}"
}
</#if>
