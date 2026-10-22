# Spec Delta

## MODIFIED Requirements

### Requirement: Problem details are read
The frontend SHALL read error responses in the problem-details format (`type`, `title`, `status`, `detail`, plus extension properties such as `errors`, `violation`, `reason`, `ineligibleItems`) and make them available to the page that made the request. A non-JSON or empty error body SHALL be handled using the HTTP status alone. When one status of an operation can carry several problem kinds, the page SHALL tell them apart by the problem's `type`, not by which extension properties are present.

#### Scenario: Detail shown for a conflict
- **WHEN** a request is answered `409` with a problem detail whose `detail` is "Category name already in use"
- **THEN** the page that made the request can show that text to the user

#### Scenario: Body that is not JSON
- **WHEN** an error response has an empty or non-JSON body
- **THEN** the user sees a generic message for that status and the application keeps working

#### Scenario: Several problem kinds on one status
- **WHEN** a request is answered `422` with `type` `https://voltstore.com/errors/insufficient-stock` by an operation that can also answer `ineligible-items` or `order-total-too-large` problems
- **THEN** the page that made the request receives it as an insufficient-stock problem with its `insufficientItems`, and not as one of the other kinds

#### Scenario: Unknown problem type
- **WHEN** an error response carries a `type` the page does not expect for that status
- **THEN** the page handles it by its status alone, as for a problem without extensions

### Requirement: Field validation errors on forms
For a `400` response with an `errors` map, the frontend SHALL show each message next to the form field with the matching name. Messages for fields that the form does not show SHALL be shown in a general error area of that form. A `400` response without an `errors` map SHALL be shown in the general error area.

#### Scenario: Server rejects a field
- **WHEN** a form is submitted and the answer is `400` with `errors: { "email": ["must be a well-formed email address"] }`
- **THEN** the message is shown at the email field and the other entered values are kept

#### Scenario: Error for a field not on the form
- **WHEN** the `errors` map names a field the form does not show
- **THEN** the message appears in the form's general error area

#### Scenario: Bad request without field errors
- **WHEN** a form is submitted and the answer is `400` with a `detail` but no `errors` map
- **THEN** the `detail` appears in the form's general error area, no field is marked, and the entered values are kept

## ADDED Requirements

### Requirement: Missing and null fields are treated alike
For every field that a service contract does not mark as always present, the frontend SHALL treat a missing field and a field answered as `null` the same way, and SHALL show both as "no value" without failing the page.

#### Scenario: Optional field answered as null
- **WHEN** an answer carries an optional field with the value `null`
- **THEN** the page shows it exactly as when the field is missing from the answer

#### Scenario: Required fields present
- **WHEN** an answer carries every field the contract marks as always present
- **THEN** the page shows those values without fallback text
