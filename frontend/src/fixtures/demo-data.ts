// Demo data shared by the mock backend (design D10) and the seed script (design D14),
// so the mock and a seeded live stack show the same catalog.
// Passwords are fixed demo values for local stacks only (documented in README.md).

import type { Role } from '../api/auth';

export interface DemoAccount {
  email: string;
  password: string;
  role: Role;
  label: string;
}

export interface DemoPackaging {
  grossWeightGrams: number;
  lengthCm: number;
  widthCm: number;
  heightCm: number;
}

export interface DemoProduct {
  title: string;
  description: string;
  category: DemoCategoryName;
  /** Catalog `imageUrl`: a Wikimedia Commons photo, credited in docs/image-credits.md. */
  image: string;
  /** UAH. */
  price: number;
  packaging: DemoPackaging;
  /** Available quantity the seed script adjusts stock to. */
  targetStock: number;
  /** Activated by the seed script; inactive ones are hidden from the storefront. */
  active: boolean;
}

/**
 * The admin is created by Auth Service at startup (EP-AUTH-JOB-01) with the
 * stack's ADMIN_PASSWORD; this password is used by the mock backend only.
 * The seed script creates the other three accounts.
 */
export const DEMO_ADMIN: DemoAccount = {
  email: 'admin@voltstore.com',
  password: 'admin-demo',
  role: 'ADMIN',
  label: 'Admin',
};

export const DEMO_STAFF: DemoAccount[] = [
  {
    email: 'manager@voltstore.com',
    password: 'manager-demo',
    role: 'CATALOG_MANAGER',
    label: 'Catalog manager',
  },
  {
    email: 'worker@voltstore.com',
    password: 'worker-demo',
    role: 'INVENTORY_WORKER',
    label: 'Inventory worker',
  },
];

export const DEMO_CUSTOMER: DemoAccount = {
  email: 'customer@voltstore.com',
  password: 'customer-demo',
  role: 'CUSTOMER',
  label: 'Customer',
};

export const DEMO_ACCOUNTS: DemoAccount[] = [DEMO_ADMIN, ...DEMO_STAFF, DEMO_CUSTOMER];

export const DEMO_CATEGORIES = ['Accessories', 'Audio', 'Laptops', 'Phones'] as const;
export type DemoCategoryName = (typeof DEMO_CATEGORIES)[number];

const phone = (g: number): DemoPackaging => ({
  grossWeightGrams: g,
  lengthCm: 18,
  widthCm: 10,
  heightCm: 6,
});
const laptop = (g: number): DemoPackaging => ({
  grossWeightGrams: g,
  lengthCm: 42,
  widthCm: 30,
  heightCm: 8,
});
const audio = (g: number): DemoPackaging => ({
  grossWeightGrams: g,
  lengthCm: 26,
  widthCm: 21,
  heightCm: 10,
});
const small = (g: number): DemoPackaging => ({
  grossWeightGrams: g,
  lengthCm: 16,
  widthCm: 11,
  heightCm: 5,
});

export const DEMO_PRODUCTS: DemoProduct[] = [
  // Phones
  {
    title: 'Apple iPhone 16, 128 GB, Black',
    description:
      '6.1-inch Super Retina XDR display, A18 chip and a 48 MP main camera. USB-C charging and up to 22 hours of video playback.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/7/72/About_iPhone_16_Ultramarine.jpg/960px-About_iPhone_16_Ultramarine.jpg',
    price: 37999,
    packaging: phone(420),
    targetStock: 14,
    active: true,
  },
  {
    title: 'Apple iPhone 16 Pro, 256 GB, Natural Titanium',
    description:
      'Titanium design, 6.3-inch ProMotion display, A18 Pro chip and a 5x telephoto camera. Records 4K video at 120 fps.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f8/IPhone_16_Pro_series.jpg/960px-IPhone_16_Pro_series.jpg',
    price: 54999,
    packaging: phone(450),
    targetStock: 6,
    active: true,
  },
  {
    title: 'Samsung Galaxy A56 5G, 256 GB',
    description:
      '6.7-inch Super AMOLED display at 120 Hz, a 5000 mAh battery and six years of software updates.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/6/64/SmsnGlxA565gBack2026040500.jpg/960px-SmsnGlxA565gBack2026040500.jpg',
    price: 17499,
    packaging: phone(410),
    targetStock: 25,
    active: true,
  },
  {
    title: 'Samsung Galaxy S25, 256 GB, Navy',
    description:
      'Compact flagship with a 6.2-inch display, Snapdragon 8 Elite and a triple camera with 3x optical zoom.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0e/Samsung_Galaxy_S25%2B.jpg/960px-Samsung_Galaxy_S25%2B.jpg',
    price: 39999,
    packaging: phone(400),
    targetStock: 9,
    active: true,
  },
  {
    title: 'Google Pixel 9a, 128 GB, Obsidian',
    description:
      'Clean Android with seven years of updates, a 6.3-inch display and the Tensor G4 chip. IP68 water and dust resistance.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cc/Pixel_9a_back.jpg/960px-Pixel_9a_back.jpg',
    price: 21999,
    packaging: phone(390),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Xiaomi Redmi Note 14 Pro, 256 GB',
    description:
      'A 200 MP main camera, 6.67-inch AMOLED display and 45 W fast charging in an IP64 body.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/7/71/Redmi_Note_14_Pro_%281%29.jpg/960px-Redmi_Note_14_Pro_%281%29.jpg',
    price: 13999,
    packaging: phone(400),
    targetStock: 30,
    active: true,
  },
  {
    title: 'Motorola Edge 50 Fusion, 256 GB',
    description: 'Curved 6.7-inch pOLED display, 68 W charging and a vegan leather back.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0e/Motorola_Edge_50_Neo.jpg/960px-Motorola_Edge_50_Neo.jpg',
    price: 14499,
    packaging: phone(390),
    targetStock: 12,
    active: true,
  },
  {
    title: 'OnePlus 13R, 256 GB, Nebula Noir',
    description:
      'Snapdragon 8 Gen 3, a 6000 mAh battery with 80 W charging and a bright 6.78-inch display.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3b/OnePlus_13R_back.jpg/960px-OnePlus_13R_back.jpg',
    price: 24999,
    packaging: phone(420),
    targetStock: 7,
    active: true,
  },
  {
    title: 'Nothing Phone (3a), 256 GB',
    description:
      'Glyph interface on a transparent back, 6.77-inch AMOLED and a 50 MP periscope camera.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/7/78/Nothing_Phone_%283a%29_Pro_20250628.jpg/960px-Nothing_Phone_%283a%29_Pro_20250628.jpg',
    price: 16999,
    packaging: phone(400),
    targetStock: 10,
    active: true,
  },
  {
    title: 'Samsung Galaxy Z Flip6, 256 GB',
    description: 'Foldable phone with a 3.4-inch cover screen and a 6.7-inch main display.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f5/Samsung_Galaxy_Z_Flip_6.jpg/960px-Samsung_Galaxy_Z_Flip_6.jpg',
    price: 42999,
    packaging: phone(430),
    targetStock: 3,
    active: true,
  },
  {
    title: 'Apple iPhone 15, 128 GB, Blue',
    description: 'Dynamic Island, a 48 MP main camera and USB-C on a 6.1-inch phone.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c1/About_iPhone_15_Blue.jpg/960px-About_iPhone_15_Blue.jpg',
    price: 31999,
    packaging: phone(410),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Xiaomi 14T, 512 GB',
    description: 'Leica-tuned cameras, a 144 Hz display and 67 W charging.',
    category: 'Phones',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/4/48/Xiaomi_14T_Pro.jpg/960px-Xiaomi_14T_Pro.jpg',
    price: 23999,
    packaging: phone(420),
    targetStock: 5,
    active: false,
  },

  // Laptops
  {
    title: 'ASUS Vivobook 15 OLED, Core i5, 16 GB',
    description:
      '15.6-inch OLED display, Intel Core i5-13500H, 16 GB RAM and a 512 GB SSD. Weighs 1.7 kg.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d3/Asus_Vivobook_15.jpg/960px-Asus_Vivobook_15.jpg',
    price: 27499,
    packaging: laptop(2600),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Lenovo IdeaPad Slim 5 14", Ryzen 7',
    description:
      'AMD Ryzen 7 8845HS, 16 GB RAM, 1 TB SSD and a 14-inch 2.8K OLED screen in an aluminium body.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b8/Lenovo_IdeaPad_Flex14_laptop.jpg/960px-Lenovo_IdeaPad_Flex14_laptop.jpg',
    price: 32999,
    packaging: laptop(2400),
    targetStock: 8,
    active: true,
  },
  {
    title: 'Apple MacBook Air 13", M3, 16 GB, 256 GB',
    description:
      'Fanless design with the M3 chip, a 13.6-inch Liquid Retina display and up to 18 hours of battery life.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/2/25/M2_Macbook_Air_Midnight_model_-_1.jpg/960px-M2_Macbook_Air_Midnight_model_-_1.jpg',
    price: 49999,
    packaging: laptop(2100),
    targetStock: 11,
    active: true,
  },
  {
    title: 'Apple MacBook Pro 14", M4 Pro, 24 GB, 512 GB',
    description: 'Liquid Retina XDR display, M4 Pro with a 14-core CPU and Thunderbolt 5 ports.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/4/43/MacBook_Pro_%2816-inch%2C_M4_Pro%2C_Silver%29.jpg/960px-MacBook_Pro_%2816-inch%2C_M4_Pro%2C_Silver%29.jpg',
    price: 94999,
    packaging: laptop(2900),
    targetStock: 4,
    active: true,
  },
  {
    title: 'HP Pavilion 15, Core i7, 16 GB, 1 TB',
    description: 'A 15.6-inch Full HD IPS display, Intel Core i7-1355U and a full-size keyboard.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a7/HP_Pavilion_laptop.jpg/960px-HP_Pavilion_laptop.jpg',
    price: 34999,
    packaging: laptop(2700),
    targetStock: 6,
    active: true,
  },
  {
    title: 'Acer Nitro V 15, RTX 4050, Core i5',
    description: 'Gaming laptop with GeForce RTX 4050, a 144 Hz display and 16 GB DDR5 memory.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e9/Acer_Nitro_V_15_2026-08-15_gaming_laptop_02.jpg/960px-Acer_Nitro_V_15_2026-08-15_gaming_laptop_02.jpg',
    price: 38999,
    packaging: laptop(3300),
    targetStock: 9,
    active: true,
  },
  {
    title: 'Dell Inspiron 14, Core Ultra 5, 16 GB',
    description:
      'Intel Core Ultra 5 with an AI engine, a 14-inch 16:10 display and a 1080p webcam.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f6/Dell_Inspiron_15_7000_%287559%29_%281%29.jpg/960px-Dell_Inspiron_15_7000_%287559%29_%281%29.jpg',
    price: 36999,
    packaging: laptop(2500),
    targetStock: 5,
    active: true,
  },
  {
    title: 'Microsoft Surface Laptop 7, 13.8", Snapdragon X Plus',
    description: 'Copilot+ PC with a touch display, up to 20 hours of video playback and Wi-Fi 7.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3a/Microsoft_Surface_Laptop_7.jpg/960px-Microsoft_Surface_Laptop_7.jpg',
    price: 52999,
    packaging: laptop(2400),
    targetStock: 2,
    active: true,
  },
  {
    title: 'ASUS ROG Zephyrus G14, RTX 4060',
    description: 'A 14-inch 3K OLED at 120 Hz, Ryzen 9 and GeForce RTX 4060 in a 1.5 kg chassis.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c6/ASUS_ROG_Zephyrus_2026-08-15_G14.jpg/960px-ASUS_ROG_Zephyrus_2026-08-15_G14.jpg',
    price: 79999,
    packaging: laptop(2800),
    targetStock: 3,
    active: true,
  },
  {
    title: 'Lenovo ThinkPad E14 Gen 6, Ryzen 5',
    description:
      'Business laptop with a spill-resistant keyboard, fingerprint reader and 16 GB RAM.',
    category: 'Laptops',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c5/Lenovo_ThinkPad_E14_G7_14_WUXGA.jpg/960px-Lenovo_ThinkPad_E14_G7_14_WUXGA.jpg',
    price: 33999,
    packaging: laptop(2500),
    targetStock: 0,
    active: false,
  },

  // Audio
  {
    title: 'Sony WH-1000XM5 headphones',
    description:
      'Over-ear wireless headphones with adaptive noise cancelling and up to 30 hours of playback. Connects to two devices at once.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/df/Sony-WH-1000XM3-kabellose-Bluetooth-Noise-Cancelling-Kopfhoerer.3.jpg/960px-Sony-WH-1000XM3-kabellose-Bluetooth-Noise-Cancelling-Kopfhoerer.3.jpg',
    price: 12999,
    packaging: audio(420),
    targetStock: 18,
    active: true,
  },
  {
    title: 'Apple AirPods Pro 2, USB-C',
    description:
      'Active noise cancellation, adaptive audio and a MagSafe charging case with a speaker.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/9/97/AirPods_Pro_2.jpg/960px-AirPods_Pro_2.jpg',
    price: 9999,
    packaging: small(180),
    targetStock: 22,
    active: true,
  },
  {
    title: 'JBL Flip 6 portable speaker',
    description: 'Waterproof and dustproof (IP67) Bluetooth speaker with 12 hours of playtime.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a2/JBL_Flip_4.jpg/960px-JBL_Flip_4.jpg',
    price: 4299,
    packaging: audio(750),
    targetStock: 16,
    active: true,
  },
  {
    title: 'Samsung Galaxy Buds3 Pro',
    description: 'Blade-design earbuds with intelligent noise cancelling and 24-bit Hi-Fi sound.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9e/Samsung_Galaxy_Buds_FE_and_Samsung_Galaxy_Buds2_Pro.jpg/960px-Samsung_Galaxy_Buds_FE_and_Samsung_Galaxy_Buds2_Pro.jpg',
    price: 8499,
    packaging: small(160),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Xiaomi Redmi Buds 6 Pro',
    description:
      'Hybrid noise cancelling up to 55 dB, dual drivers and up to 36 hours with the case.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a0/Samsung_Galaxy_Buds_Live.jpg/960px-Samsung_Galaxy_Buds_Live.jpg',
    price: 2499,
    packaging: small(150),
    targetStock: 40,
    active: true,
  },
  {
    title: 'Marshall Emberton III speaker',
    description:
      'Compact speaker with 360-degree sound, 32+ hours of playtime and IP67 protection.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/c/ca/Marshall_Speaker.jpg/960px-Marshall_Speaker.jpg',
    price: 7499,
    packaging: audio(900),
    targetStock: 7,
    active: true,
  },
  {
    title: 'Bose QuietComfort Ultra headphones',
    description: 'Immersive spatial audio and world-class noise cancelling in a foldable design.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d6/Bose_QuietComfort_35_II_Wireless_Headphones.jpg/960px-Bose_QuietComfort_35_II_Wireless_Headphones.jpg',
    price: 17999,
    packaging: audio(480),
    targetStock: 4,
    active: true,
  },
  {
    title: 'Sennheiser Momentum 4 Wireless',
    description:
      'Up to 60 hours of battery life, adaptive noise cancellation and audiophile sound.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/3/32/Momentum1_0.jpg/960px-Momentum1_0.jpg',
    price: 13499,
    packaging: audio(450),
    targetStock: 6,
    active: true,
  },
  {
    title: 'JBL Tune 520BT headphones',
    description: 'Light on-ear headphones with JBL Pure Bass sound and 57 hours of playtime.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c4/JBL_LIVE_650_BTNC_%28Over-Ear_Noise_Cancelling_Headphone%29._%2851127342181%29.jpg/960px-JBL_LIVE_650_BTNC_%28Over-Ear_Noise_Cancelling_Headphone%29._%2851127342181%29.jpg',
    price: 1799,
    packaging: audio(300),
    targetStock: 35,
    active: true,
  },
  {
    title: 'Sonos Era 100 speaker',
    description: 'Wi-Fi and Bluetooth smart speaker with stereo sound and Trueplay tuning.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0f/White_sonos_one_speaker_-_horizontal_%2825472316397%29.jpg/960px-White_sonos_one_speaker_-_horizontal_%2825472316397%29.jpg',
    price: 11999,
    packaging: audio(2600),
    targetStock: 3,
    active: true,
  },
  {
    title: 'Anker Soundcore Liberty 4 NC',
    description: 'Adaptive noise cancelling earbuds with up to 50 hours of playtime and LDAC.',
    category: 'Audio',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cb/ANKER_Zolo_Liberty-6_%2839531463541%29.jpg/960px-ANKER_Zolo_Liberty-6_%2839531463541%29.jpg',
    price: 3299,
    packaging: small(170),
    targetStock: 20,
    active: true,
  },

  // Accessories
  {
    title: 'Anker 737 power bank, 24,000 mAh',
    description: '140 W output, a smart display and charging for a laptop and a phone at once.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ee/Anker_power_bank_and_cable.jpg/960px-Anker_power_bank_and_cable.jpg',
    price: 4899,
    packaging: small(700),
    targetStock: 13,
    active: true,
  },
  {
    title: 'Baseus 65 W GaN charger, 3 ports',
    description: 'Two USB-C and one USB-A port; charges a laptop at full speed.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d3/Lazos_GaN_Charger_30W_L-AC-G30.jpg/960px-Lazos_GaN_Charger_30W_L-AC-G30.jpg',
    price: 1599,
    packaging: small(220),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Ugreen USB-C cable 2 m, 100 W',
    description: 'Braided USB-C to USB-C cable for 100 W charging and 480 Mbps data.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c3/USB-C_cable_2017_A.jpg/960px-USB-C_cable_2017_A.jpg',
    price: 399,
    packaging: small(90),
    targetStock: 60,
    active: true,
  },
  {
    title: 'Apple MagSafe charger, 1 m',
    description: 'Magnetic wireless charging up to 25 W for recent iPhone models.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/0/01/MagSafe_and_USB-C_Cable_Charger_for_iPhone.jpg/960px-MagSafe_and_USB-C_Cable_Charger_for_iPhone.jpg',
    price: 1999,
    packaging: small(150),
    targetStock: 15,
    active: true,
  },
  {
    title: 'Logitech MX Master 3S mouse',
    description:
      'Quiet clicks, an 8000 DPI sensor and MagSpeed scrolling; pairs with three devices.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/6/61/Logitech_MX_Master_3S_HS02.jpg/960px-Logitech_MX_Master_3S_HS02.jpg',
    price: 4299,
    packaging: small(350),
    targetStock: 12,
    active: true,
  },
  {
    title: 'Logitech MX Keys S keyboard',
    description: 'Backlit low-profile keyboard with smart illumination and multi-device switching.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/7/78/Logitech_MX_Keys_YR0073_Wireless_Keyboard.jpg/960px-Logitech_MX_Keys_YR0073_Wireless_Keyboard.jpg',
    price: 4999,
    packaging: laptop(1100),
    targetStock: 8,
    active: true,
  },
  {
    title: 'Samsung 45 W USB-C charger',
    description: 'Super Fast Charging 2.0 wall charger with PPS support.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/4/45/SAMSUNG_EP-T4510_45W_POWER_ADAPER_BLACK_%26_USB_C_TO_C_CABLE.jpg/960px-SAMSUNG_EP-T4510_45W_POWER_ADAPER_BLACK_%26_USB_C_TO_C_CABLE.jpg',
    price: 1199,
    packaging: small(200),
    targetStock: 25,
    active: true,
  },
  {
    title: 'Spigen Ultra Hybrid case for iPhone 16',
    description: 'Clear shock-absorbing case with raised edges for the screen and camera.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7b/Spigen_Tough_Armor_on_iPhone_6_Plus.jpg/960px-Spigen_Tough_Armor_on_iPhone_6_Plus.jpg',
    price: 699,
    packaging: small(80),
    targetStock: 45,
    active: true,
  },
  {
    title: 'Xiaomi Smart Band 9',
    description: 'Fitness tracker with an AMOLED display, 21-day battery and 150+ workout modes.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e9/Xiaomi_Mi_Band_8.jpg/960px-Xiaomi_Mi_Band_8.jpg',
    price: 1699,
    packaging: small(160),
    targetStock: 18,
    active: true,
  },
  {
    title: 'SanDisk Extreme 1 TB portable SSD',
    description: 'Rugged USB-C SSD with up to 1050 MB/s read speed and IP65 protection.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7c/SanDisk_Extreme_Portable_SSD_1_TB_-_front_view.jpg/960px-SanDisk_Extreme_Portable_SSD_1_TB_-_front_view.jpg',
    price: 4799,
    packaging: small(180),
    targetStock: 9,
    active: true,
  },
  {
    title: 'TP-Link Archer AX55 Wi-Fi 6 router',
    description: 'Dual-band AX3000 router with four antennas and OneMesh support.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3a/TP-link_Archer_A6_Router_%28v4%29_-_Front_Side.jpg/960px-TP-link_Archer_A6_Router_%28v4%29_-_Front_Side.jpg',
    price: 2999,
    packaging: laptop(1200),
    targetStock: 6,
    active: true,
  },
  {
    title: 'Belkin 3-in-1 MagSafe stand',
    description: 'Charges an iPhone, an Apple Watch and AirPods at the same time.',
    category: 'Accessories',
    image:
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/0/07/JUPW1107NP_MagSafe_15W_Wireless_Charging_Stand.jpg/960px-JUPW1107NP_MagSafe_15W_Wireless_Charging_Stand.jpg',
    price: 5499,
    packaging: small(800),
    targetStock: 0,
    active: false,
  },
];

export interface DemoCartLine {
  /** A fixture product title; the mock and the seed resolve it to the product id. */
  title: string;
  quantity: number;
}

/**
 * The demo customer's cart (change customer-cart, design D10): two in-stock lines
 * and one inactive product, so the cart page shows an unavailable line and a
 * guest who logs in as the demo customer is asked to replace, keep or merge.
 */
export const DEMO_CART: DemoCartLine[] = [
  { title: 'Sony WH-1000XM5 headphones', quantity: 1 },
  { title: 'Anker 737 power bank, 24,000 mAh', quantity: 2 },
  { title: 'Xiaomi 14T, 512 GB', quantity: 1 },
];

/**
 * The demo customer's profile (change customer-checkout-orders, design D11): the
 * recipient of the checkout mockup, so the delivery form is pre-filled.
 */
export const DEMO_PROFILE = {
  firstName: 'Olena',
  lastName: 'Kovalenko',
  phone: '+380501234567',
  defaultAddress: 'Kyiv, branch No. 12, Velyka Vasylkivska St 72',
};
