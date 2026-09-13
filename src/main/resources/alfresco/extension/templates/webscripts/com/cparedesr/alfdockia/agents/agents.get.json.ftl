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
    "count": ${data.count?c},
    "items": [
      <#list data.items as it>
      {
        "agentId": "${it.agentId?js_string}",
        "name": "${it.name?js_string}",
        "image": "${it.image?js_string}",
        "desiredState": "${it.desiredState?js_string}",
        "currentState": "${it.currentState?js_string}",
        "health": <#if it.health??>"${it.health?js_string}"<#else>null</#if>,
        "containerId": <#if it.containerId??>"${it.containerId?js_string}"<#else>null</#if>,
        "nodeId": <#if it.nodeId??>"${it.nodeId?js_string}"<#else>null</#if>,
        "createdAt": <#if it.createdAt??>"${it.createdAt?js_string}"<#else>null</#if>,
        "updatedAt": <#if it.updatedAt??>"${it.updatedAt?js_string}"<#else>null</#if>
      }<#if it_has_next>,</#if>
      </#list>
    ]
  }
}
</#if>
