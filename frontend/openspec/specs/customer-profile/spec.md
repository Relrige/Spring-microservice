# customer-profile Specification

## Purpose

The customer's profile kept by Customer Service (name, phone, default address): a page to view and save it, and its use as the source of delivery details at checkout.

## Requirements

### Requirement: Profile page
`/account/profile` SHALL show the customer's profile from `GET /customers/me/profile` in a form with first name, last name, phone and default address. A customer who never saved a profile SHALL see an empty form, not an error.

#### Scenario: Saved profile is shown
- **WHEN** a customer with a saved profile opens `/account/profile`
- **THEN** the form shows their first name, last name, phone and default address

#### Scenario: No profile yet
- **WHEN** a customer whose profile answer has every field `null` opens `/account/profile`
- **THEN** the form is empty and no error is shown

### Requirement: Saving the profile
Saving SHALL send `PUT /customers/me/profile` and show the saved values from the answer with a confirmation. The form SHALL check the rules of EP-CUST-02 before sending: first and last name required, at most 100 characters; phone required in international format (`+` and 8–15 digits, spaces allowed while typing and removed before sending); default address optional, at most 500 characters.

#### Scenario: Profile saved
- **WHEN** the customer enters "Olena", "Kovalenko", "+380 50 123 45 67" and an address and saves
- **THEN** `PUT /customers/me/profile` is sent with phone `+380501234567` and "Profile saved" is shown

#### Scenario: Invalid phone
- **WHEN** the customer enters the phone "0501234567" and saves
- **THEN** no request is sent and the phone field says to use the international format, for example +380501234567

#### Scenario: Server field errors
- **WHEN** the save is answered `400` with `errors` for `phone`
- **THEN** the message is shown on the phone field and the entered values are kept

### Requirement: Profile pre-fills delivery details
At checkout the delivery details SHALL be pre-filled from the profile: the recipient from first and last name, the phone, and the address from the default address. Fields without a profile value SHALL stay empty. If the profile cannot be loaded, the checkout SHALL still open with an empty form.

#### Scenario: Pre-filled checkout
- **WHEN** a customer with a saved profile opens the checkout
- **THEN** recipient, phone and address show the profile's values

#### Scenario: Profile unavailable
- **WHEN** `GET /customers/me/profile` fails with `503` while the checkout opens
- **THEN** the delivery form is shown empty and can be filled in by hand
