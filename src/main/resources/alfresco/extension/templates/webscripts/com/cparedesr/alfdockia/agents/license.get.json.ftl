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
    "currentAgents": ${data.currentAgents?c},
    "license": {
      "valid": ${data.license.valid?c},
      "edition": "${data.license.edition?js_string}",
      "issuer": "${data.license.issuer?js_string}",
      "product": "${data.license.product?js_string}",
      "licenseId": <#if data.license.licenseId??>"${data.license.licenseId?js_string}"<#else>null</#if>,
      "licensee": <#if data.license.licensee??>"${data.license.licensee?js_string}"<#else>null</#if>,
      "maxAgents": ${data.license.maxAgents?c},
      "unlimitedAgents": ${data.license.unlimitedAgents?c},
      "issuedAt": <#if data.license.issuedAt??>"${data.license.issuedAt?js_string}"<#else>null</#if>,
      "expiresAt": <#if data.license.expiresAt??>"${data.license.expiresAt?js_string}"<#else>null</#if>,
      "loadedFrom": <#if data.license.loadedFrom??>"${data.license.loadedFrom?js_string}"<#else>null</#if>,
      "reason": <#if data.license.reason??>"${data.license.reason?js_string}"<#else>null</#if>,
      "features": [
        <#list data.license.features as feature>
        "${feature?js_string}"<#if feature_has_next>,</#if>
        </#list>
      ]
    }
  }
}
</#if>
