package tagresource

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/\w+\/\w+\/tags\//),
        test('/no_skatteetaten_aurora_demo/whoami/tags/')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/TagResource.json'))
  }
}