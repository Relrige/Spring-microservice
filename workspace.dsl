workspace "VoltStore" "Архітектура платформи VoltStore на базі мікросервісів та DDD" {

    model {
        // ==========================================
        // 1. Актори (Користувачі)
        // ==========================================
        customer = person "Покупець" "Клієнт інтернет-магазину: обирає техніку, оформлює замовлення, оплачує онлайн та відстежує доставку." "Customer"
        warehouseStaff = person "Працівник складу" "Персонал складу: комплектує замовлення, веде облік залишків та передає посилки перевізникам." "Staff"

        // ==========================================
        // 2. Зовнішні системи
        // ==========================================
        paymentGateway = softwareSystem "Банківський еквайринг" "Зовнішній платіжний шлюз для безготівкової оплати картками та реверсу коштів." "External"
        carrierService = softwareSystem "Поштовий оператор" "Зовнішні логістичні служби («Нова Пошта», Meest) для формування ТТН та доставки." "External"

        // ==========================================
        // 3. Програмна система VoltStore
        // ==========================================
        voltStore = softwareSystem "Платформа VoltStore" "Онлайн-магазин техніки з управлінням складом, платежами та логістикою." {

            // --------------------------------------
            // Фронтенд та шлюз
            // --------------------------------------
            storefront = container "Веб-вітрина" "Клієнтський веб-інтерфейс для каталогу, кошика, чекауту та трекінгу." "React / TypeScript (SPA)" "WebBrowser"
            apiGateway = container "API Gateway" "Єдина точка входу: маршрутизація запитів, безпека та лімітування." "Spring Cloud Gateway" "Gateway"

            // --------------------------------------
            // Контекст 1: Catalog Service
            // --------------------------------------
            catalogService = container "Сервіс каталогу" "Управління асортиментом техніки, категоріями, характеристиками та цінами." "Java / Spring Boot" "Microservice" {
                catalogController = component "Catalog Controller" "REST API перегляду товарів, фільтрації та характеристик." "Spring MVC Controller"
                catalogDomainService = component "Catalog Service" "Бізнес-логіка каталогу, валідація цін та пошук." "Spring Service"
                productRepository = component "Product Repository" "Доступ до товарів та категорій у БД." "Spring Data JPA"
            }
            catalogDb = container "БД Каталогу" "Зберігає каталог товарів, категорій та публічних цін." "PostgreSQL" "Database"

            // --------------------------------------
            // Контекст 2: Order Service
            // --------------------------------------
            orderService = container "Сервіс замовлень" "Життєвий цикл замовлень, розрахунок суми, збереження зрізів та оркестрація купівлі." "Java / Spring Boot" "Microservice" {
                orderController = component "Order Controller" "REST API створення, скасування та відстеження замовлень." "Spring MVC Controller"
                orderOrchestrator = component "Order Orchestrator" "Оркестрація чекауту, фіксація зрізів, виклик складу, оплати й доставки." "Spring Service"
                orderCatalogClient = component "Catalog Client" "Отримання цін і створення незмінного зрізу OrderItemSnapshot." "Spring Cloud OpenFeign"
                orderInventoryClient = component "Inventory Client" "Запити резервування та скасування резерву на складі." "Spring Cloud OpenFeign"
                orderPaymentClient = component "Payment Client" "Ініціація авторизації оплати та запитів на повернення коштів." "Spring Cloud OpenFeign"
                orderDeliveryClient = component "Delivery Client" "Запити на реєстрацію накладних ТТН та анулювання доставки." "Spring Cloud OpenFeign"
                orderRepository = component "Order Repository" "Доступ до замовлень, зрізів та статусів у БД." "Spring Data JPA"
            }
            orderDb = container "БД Замовлень" "Зберігає замовлення, зрізи товарів (OrderItemSnapshot) та доставки." "PostgreSQL" "Database"

            // --------------------------------------
            // Контекст 3: Inventory Service
            // --------------------------------------
            inventoryService = container "Сервіс складу" "Кількісний облік залишків, тимчасове резервування та габарити посилок." "Java / Spring Boot" "Microservice" {
                inventoryController = component "Inventory Controller" "REST API перевірки наявності, резервування та розблокування товару." "Spring MVC Controller"
                inventoryDomainService = component "Inventory Service" "Логіка резервування, запобігання overselling та тайм-аути резервів." "Spring Service"
                stockItemRepository = component "Stock Item Repository" "Доступ до залишків (StockItem), ваги та габаритів у БД." "Spring Data JPA"
                stockReservationRepository = component "Stock Reservation Repository" "Доступ до записів резервування (StockReservation) у БД." "Spring Data JPA"
            }
            inventoryDb = container "БД Складу" "Зберігає залишки техніки, характеристики коробок та активні резерви." "PostgreSQL" "Database"

            // --------------------------------------
            // Контекст 4: Payment Service
            // --------------------------------------
            paymentService = container "Сервіс платежів" "Обробка платежів, списання, повернення коштів та адаптація банківського API." "Java / Spring Boot" "Microservice" {
                paymentController = component "Payment Controller" "REST API проведення оплат та оформлення повернень." "Spring MVC Controller"
                paymentDomainService = component "Payment Service" "Транзакційна логіка оплат, перевірка ідемпотентності та статуси." "Spring Service"
                bankAcl = component "Bank ACL Adapter" "Антикорупційний шар: трансляція транзакцій у протокол банку." "Spring Component (ACL)" "ACL"
                paymentRepository = component "Payment Repository" "Доступ до платіжних транзакцій та квитанцій у БД." "Spring Data JPA"
            }
            paymentDb = container "БД Платежів" "Зберігає історію транзакцій (PaymentTransaction) та банківські відповіді." "PostgreSQL" "Database"

            // --------------------------------------
            // Контекст 5: Delivery Service
            // --------------------------------------
            deliveryService = container "Сервіс доставки" "Формування відправлень, зрізи вантажу (ParcelSnapshot), отримання ТТН та трекінг." "Java / Spring Boot" "Microservice" {
                deliveryController = component "Delivery Controller" "REST API реєстрації відправлень, отримання ТТН та скасування." "Spring MVC Controller"
                deliveryDomainService = component "Delivery Service" "Формування посилок, вибір перевізника та розрахунок параметрів." "Spring Service"
                carrierAcl = component "Carrier ACL Adapter" "Антикорупційний шар: конвертація посилки у формат API перевізника та отримання ТТН." "Spring Component (ACL)" "ACL"
                deliveryRepository = component "Delivery Repository" "Доступ до накладних (Consignment) та номерів ТТН у БД." "Spring Data JPA"
            }
            deliveryDb = container "БД Доставки" "Зберігає інформацію про відправлення, номери ТТН та дані отримувача." "PostgreSQL" "Database"
        }

        // ==========================================
        // 4. Зв'язки: Користувачі -> Система
        // ==========================================
        customer -> storefront "Переглядає товари, формує кошик, оплачує та стежить за замовленням" "HTTPS"
        customer -> paymentGateway "Вводить платіжні дані на 3D-Secure формі" "HTTPS"
        warehouseStaff -> storefront "Оновлює дані товарів та контролює статус комплектації" "HTTPS"

        // ==========================================
        // 5. Зв'язки: Вітрина та Шлюз
        // ==========================================
        storefront -> apiGateway "Надсилає API-запити клієнта" "HTTPS / JSON"
        apiGateway -> catalogService "Маршрутизує запити до каталогу" "HTTP / REST"
        apiGateway -> orderService "Маршрутизує операції із замовленнями" "HTTP / REST"
        apiGateway -> deliveryService "Маршрутизує запити трекінгу ТТН" "HTTP / REST"

        // ==========================================
        // 6. Міжсервісна взаємодія (Context Map)
        // ==========================================
        // Catalog -> Order (Upstream / Downstream)
        orderService -> catalogService "Запитує ціни для фіксації зрізу OrderItemSnapshot [U/D]" "HTTP / REST"

        // Inventory <-> Order (Customer / Supplier)
        orderService -> inventoryService "Резервує залишки або скасовує резерв [C/S]" "HTTP / REST"

        // Order -> Payment (Customer / Supplier)
        orderService -> paymentService "Ініціює списання та повернення коштів [C/S]" "HTTP / REST"

        // Payment -> Bank (Downstream ACL)
        paymentService -> paymentGateway "Виконує авторизацію, списання та повернення [ACL]" "HTTPS / REST"

        // Order -> Delivery (Customer / Supplier)
        orderService -> deliveryService "Реєструє доставку або анулює накладну [C/S]" "HTTP / REST"

        // Delivery -> Postal Carrier (Downstream ACL)
        deliveryService -> carrierService "Створює ТТН або скасовує відправлення [ACL]" "HTTPS / REST"

        // ==========================================
        // 7. Зв'язки: Сервіси -> Бази даних
        // ==========================================
        catalogService -> catalogDb "Зберігає та читає товари й ціни" "JDBC / PostgreSQL"
        orderService -> orderDb "Зберігає замовлення та зрізи" "JDBC / PostgreSQL"
        inventoryService -> inventoryDb "Зберігає залишки та резервації" "JDBC / PostgreSQL"
        paymentService -> paymentDb "Зберігає платіжні транзакції" "JDBC / PostgreSQL"
        deliveryService -> deliveryDb "Зберігає накладні та ТТН" "JDBC / PostgreSQL"

        // ==========================================
        // 8. Внутрішні компоненти сервісів
        // ==========================================
        // Catalog Service
        catalogController -> catalogDomainService "Передає запити каталогу"
        catalogDomainService -> productRepository "Запитує сутності товарів"
        productRepository -> catalogDb "Виконує SQL-запити" "JDBC"

        // Order Service
        orderController -> orderOrchestrator "Передає операції чекауту й скасування"
        orderOrchestrator -> orderCatalogClient "Запитує дані для зрізу цін"
        orderOrchestrator -> orderInventoryClient "Надсилає запит резервування"
        orderOrchestrator -> orderPaymentClient "Надсилає запит на оплату"
        orderOrchestrator -> orderDeliveryClient "Надсилає запит створення ТТН"
        orderOrchestrator -> orderRepository "Зберігає замовлення та зрізи"
        orderCatalogClient -> catalogService "Отримує зріз товару" "HTTP / REST"
        orderInventoryClient -> inventoryService "Резервує/звільняє товар" "HTTP / REST"
        orderPaymentClient -> paymentService "Оплачує/повертає кошти" "HTTP / REST"
        orderDeliveryClient -> deliveryService "Реєструє/анулює ТТН" "HTTP / REST"
        orderRepository -> orderDb "Виконує SQL-запити" "JDBC"

        // Inventory Service
        inventoryController -> inventoryDomainService "Передає команди резервування"
        inventoryDomainService -> stockItemRepository "Перевіряє та змінює залишки"
        inventoryDomainService -> stockReservationRepository "Створює/оновлює резервації"
        stockItemRepository -> inventoryDb "Виконує SQL-запити" "JDBC"
        stockReservationRepository -> inventoryDb "Виконує SQL-запити" "JDBC"

        // Payment Service
        paymentController -> paymentDomainService "Передає операції оплати"
        paymentDomainService -> bankAcl "Ініціює виклик банківського API"
        bankAcl -> paymentGateway "Надсилає фінансовий запит" "HTTPS / REST"
        paymentDomainService -> paymentRepository "Фіксує статус транзакції"
        paymentRepository -> paymentDb "Виконує SQL-запити" "JDBC"

        // Delivery Service
        deliveryController -> deliveryDomainService "Передає команди оформлення доставки"
        deliveryDomainService -> carrierAcl "Передає зріз посилки ParcelSnapshot"
        carrierAcl -> carrierService "Реєструє посилку в системі пошти" "HTTPS / REST"
        deliveryDomainService -> deliveryRepository "Зберігає згенеровану ТТН"
        deliveryRepository -> deliveryDb "Виконує SQL-запити" "JDBC"


    }

    views {
        // ------------------------------------------
        // C4 Рівень 1: Системний контекст
        // ------------------------------------------
        systemContext voltStore "SystemContext" "C4 Рівень 1: Системний контекст VoltStore" {
            include *
            autoLayout lr
        }

        // ------------------------------------------
        // C4 Рівень 2: Контейнери
        // ------------------------------------------
        container voltStore "Containers" "C4 Рівень 2: Контейнери мікросервісної архітектури VoltStore" {
            include *
            autoLayout lr
        }

        // ------------------------------------------
        // C4 Рівень 3: Компоненти
        // ------------------------------------------
        component orderService "OrderServiceComponents" "C4 Рівень 3: Компоненти сервісу замовлень" {
            include *
            autoLayout tb
        }

        component paymentService "PaymentServiceComponents" "C4 Рівень 3: Компоненти сервісу платежів та Bank ACL" {
            include *
            autoLayout tb
        }

        component deliveryService "DeliveryServiceComponents" "C4 Рівень 3: Компоненти сервісу доставки та Carrier ACL" {
            include *
            autoLayout tb
        }

        component inventoryService "InventoryServiceComponents" "C4 Рівень 3: Компоненти сервісу складу" {
            include *
            autoLayout tb
        }

        component catalogService "CatalogServiceComponents" "C4 Рівень 3: Компоненти сервісу каталогу" {
            include *
            autoLayout tb
        }



        // ------------------------------------------
        // Стилі
        // ------------------------------------------
        styles {
            element "Element" {
                color #ffffff
            }
            element "Person" {
                background #08427b
                shape Person
            }
            element "Software System" {
                background #1168bd
            }
            element "External" {
                background #888888
                color #ffffff
            }
            element "Container" {
                background #438dd5
            }
            element "WebBrowser" {
                shape WebBrowser
                background #2a72b5
            }
            element "Gateway" {
                shape Pipe
                background #1f5687
            }
            element "Microservice" {
                shape RoundedBox
                background #3b7bb5
            }
            element "Database" {
                shape Cylinder
                background #1a4266
            }
            element "Component" {
                background #85bbf0
                color #000000
            }
            element "ACL" {
                background #d97706
                color #ffffff
            }
        }
    }
}
