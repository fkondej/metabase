(ns metabase-enterprise.sso.integrations.sso-settings
  "Namesapce for defining settings used by the SSO backends. This is separate as both the functions needed to support
  the SSO backends and the generic routing code used to determine which SSO backend to use need this
  information. Separating out this information creates a better dependency graph and avoids circular dependencies."
  (:require
   [metabase.integrations.common :as integrations.common]
   [metabase.models.setting :as setting :refer [defsetting]]
   [metabase.models.setting.multi-setting :refer [define-multi-setting-impl]]
   [metabase.public-settings :as public-settings]
   [metabase.util.i18n :refer [deferred-tru trs tru]]
   [metabase.util.log :as log]
   [metabase.util.schema :as su]
   [saml20-clj.core :as saml]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

(def ^:private GroupMappings
  (s/maybe {su/KeywordOrString [su/IntGreaterThanZero]}))

(def ^:private ^{:arglists '([group-mappings])} validate-group-mappings
  (s/validator GroupMappings))

(defsetting saml-identity-provider-uri
  (deferred-tru "This is the URL where your users go to log in to your identity provider. Depending on which IdP you''re
using, this usually looks like https://your-org-name.example.com or https://example.com/app/my_saml_app/abc123/sso/saml")
  :feature :sso-saml)

(s/defn ^:private validate-saml-idp-cert
  "Validate that an encoded identity provider certificate is valid, or throw an Exception."
  [idp-cert-str :- s/Str]
  (try
    (instance? java.security.cert.X509Certificate (saml/->X509Certificate idp-cert-str))
    (catch Throwable e
      (log/error e (trs "Error parsing SAML identity provider certificate"))
      (throw
       (Exception. (tru "Invalid identity provider certificate. Certificate should be a base-64 encoded string."))))))

(defsetting saml-identity-provider-certificate
  (deferred-tru "Encoded certificate for the identity provider. Depending on your IdP, you might need to download this,
open it in a text editor, then copy and paste the certificate's contents here.")
  :feature :sso-saml
  :setter  (fn [new-value]
            ;; when setting the idp cert validate that it's something we
             (when new-value
               (validate-saml-idp-cert new-value))
             (setting/set-value-of-type! :string :saml-identity-provider-certificate new-value)))

(defsetting saml-identity-provider-issuer
  (deferred-tru "This is a unique identifier for the IdP. Often referred to as Entity ID or simply 'Issuer'. Depending
on your IdP, this usually looks something like http://www.example.com/141xkex604w0Q5PN724v")
  :feature :sso-saml)

(defsetting saml-application-name
  (deferred-tru "This application name will be used for requests to the Identity Provider")
  :default "Metabase"
  :feature :sso-saml)

(defsetting saml-keystore-path
  (deferred-tru "Absolute path to the Keystore file to use for signing SAML requests")
  :feature :sso-saml)

(defsetting saml-keystore-password
  (deferred-tru "Password for opening the keystore")
  :default    "changeit"
  :sensitive? true
  :feature    :sso-saml)

(defsetting saml-keystore-alias
  (deferred-tru "Alias for the key that {0} should use for signing SAML requests"
                (public-settings/application-name-for-setting-descriptions))
  :default "metabase"
  :feature :sso-saml)

(defsetting saml-attribute-email
  (deferred-tru "SAML attribute for the user''s email address")
  :default "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress"
  :feature :sso-saml)

(defsetting saml-attribute-firstname
  (deferred-tru "SAML attribute for the user''s first name")
  :default "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/givenname"
  :feature :sso-saml)

(defsetting saml-attribute-lastname
  (deferred-tru "SAML attribute for the user''s last name")
  :default "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/surname"
  :feature :sso-saml)

(defsetting saml-group-sync
  (deferred-tru "Enable group membership synchronization with SAML.")
  :type    :boolean
  :default false
  :feature :sso-saml)

(defsetting saml-attribute-group
  (deferred-tru "SAML attribute for group syncing")
  :default "member_of"
  :feature :sso-saml)

(defsetting saml-group-mappings
  ;; Should be in the form: {"groupName": [1, 2, 3]} where keys are SAML groups and values are lists of MB groups IDs
  (deferred-tru "JSON containing SAML to {0} group mappings."
                (public-settings/application-name-for-setting-descriptions))
  :type    :json
  :cache?  false
  :default {}
  :feature :sso-saml
  :setter  (comp (partial setting/set-value-of-type! :json :saml-group-mappings) validate-group-mappings))

(defsetting saml-configured
  (deferred-tru "Are the mandatory SAML settings configured?")
  :type    :boolean
  :default false
  :feature :sso-saml
  :setter  :none
  :getter  (fn [] (boolean
                   (and (saml-identity-provider-uri)
                        (saml-identity-provider-certificate)))))

(defsetting saml-enabled
  (deferred-tru "Is SAML authentication configured and enabled?")
  :type    :boolean
  :default false
  :feature :sso-saml
  :getter  (fn []
             (if (saml-configured)
               (setting/get-value-of-type :boolean :saml-enabled)
               false)))

(defsetting jwt-identity-provider-uri
  (deferred-tru "URL of JWT based login page")
  :feature :sso-jwt)

(defsetting jwt-shared-secret
  (deferred-tru (str "String used to seed the private key used to validate JWT messages."
                     " "
                     "A hexadecimal-encoded 256-bit key (i.e., a 64-character string) is strongly recommended."))
  :type    :string
  :feature :sso-jwt)

(defsetting jwt-attribute-email
  (deferred-tru "Key to retrieve the JWT user's email address")
  :default "email"
  :feature :sso-jwt)

(defsetting jwt-attribute-firstname
  (deferred-tru "Key to retrieve the JWT user's first name")
  :default "first_name"
  :feature :sso-jwt)

(defsetting jwt-attribute-lastname
  (deferred-tru "Key to retrieve the JWT user's last name")
  :default "last_name"
  :feature :sso-jwt)

(defsetting jwt-attribute-groups
  (deferred-tru "Key to retrieve the JWT user's groups")
  :default "groups"
  :feature :sso-jwt)

(defsetting jwt-group-sync
  (deferred-tru "Enable group membership synchronization with JWT.")
  :type    :boolean
  :default false
  :feature :sso-jwt)

(defsetting jwt-group-mappings
  ;; Should be in the form: {"groupName": [1, 2, 3]} where keys are JWT groups and values are lists of MB groups IDs
  (deferred-tru "JSON containing JWT to {0} group mappings."
                (public-settings/application-name-for-setting-descriptions))
  :type    :json
  :cache?  false
  :default {}
  :feature :sso-jwt
  :setter  (comp (partial setting/set-value-of-type! :json :jwt-group-mappings) validate-group-mappings))

(defsetting jwt-configured
  (deferred-tru "Are the mandatory JWT settings configured?")
  :type    :boolean
  :default false
  :feature :sso-jwt
  :setter  :none
  :getter  (fn [] (boolean
                   (and (jwt-identity-provider-uri)
                        (jwt-shared-secret)))))

(defsetting jwt-enabled
  (deferred-tru "Is JWT authentication configured and enabled?")
  :type    :boolean
  :default false
  :feature :sso-jwt
  :getter  (fn []
             (if (jwt-configured)
               (setting/get-value-of-type :boolean :jwt-enabled)
               false)))

(define-multi-setting-impl integrations.common/send-new-sso-user-admin-email? :ee
  :getter (fn [] (setting/get-value-of-type :boolean :send-new-sso-user-admin-email?))
  :setter (fn [send-emails] (setting/set-value-of-type! :boolean :send-new-sso-user-admin-email? send-emails)))

(defsetting other-sso-enabled?
  "Are we using an SSO integration other than LDAP or Google Auth? These integrations use the `/auth/sso` endpoint for
  authorization rather than the normal login form or Google Auth button."
  :visibility :public
  :setter     :none
  :getter     (fn [] (or (saml-enabled) (jwt-enabled))))
