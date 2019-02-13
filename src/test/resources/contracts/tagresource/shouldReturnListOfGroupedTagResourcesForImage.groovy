package tagresource

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/\w+\/\w+\/tags\/semantic\//),
        test('/no_skatteetaten_aurora_demo/whoami/tags/semantic/')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/GroupedTagResource.json'))
  }
}