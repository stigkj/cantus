package imagetagresource

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/manifest\?tagUrls=.*/),
        test('/manifest?tagUrls=docker1.no/no_skatteetaten_aurora_demo/whoami/1')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/ImageTagResource.json'))
  }
}