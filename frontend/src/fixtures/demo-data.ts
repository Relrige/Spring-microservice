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
  /** Catalog `imageUrl`: a linked product image; sources in docs/image-credits.md. */
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
    image: 'https://fdn2.gsmarena.com/vv/bigpic/apple-iphone-16.jpg',
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
    image: 'https://fdn2.gsmarena.com/vv/bigpic/apple-iphone-16-pro.jpg',
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
    image: 'https://fdn2.gsmarena.com/vv/bigpic/samsung-galaxy-a56-.jpg',
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
    image: 'https://fdn2.gsmarena.com/vv/bigpic/samsung-galaxy-s25-sm-s931.jpg',
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
    image: 'https://fdn2.gsmarena.com/vv/bigpic/google-pixel-9a.jpg',
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
    image: 'https://fdn2.gsmarena.com/vv/bigpic/xiaomi-redmi-note-14-pro-5g.jpg',
    price: 13999,
    packaging: phone(400),
    targetStock: 30,
    active: true,
  },
  {
    title: 'Motorola Edge 50 Fusion, 256 GB',
    description: 'Curved 6.7-inch pOLED display, 68 W charging and a vegan leather back.',
    category: 'Phones',
    image: 'https://fdn2.gsmarena.com/vv/bigpic/motorola-edge-50-fusion.jpg',
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
    image: 'https://fdn2.gsmarena.com/vv/bigpic/oneplus-13r.jpg',
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
    image: 'https://fdn2.gsmarena.com/vv/bigpic/nothing-phone-3a.jpg',
    price: 16999,
    packaging: phone(400),
    targetStock: 10,
    active: true,
  },
  {
    title: 'Samsung Galaxy Z Flip6, 256 GB',
    description: 'Foldable phone with a 3.4-inch cover screen and a 6.7-inch main display.',
    category: 'Phones',
    image: 'https://fdn2.gsmarena.com/vv/bigpic/samsung-galaxy-z-flip6.jpg',
    price: 42999,
    packaging: phone(430),
    targetStock: 3,
    active: true,
  },
  {
    title: 'Apple iPhone 15, 128 GB, Blue',
    description: 'Dynamic Island, a 48 MP main camera and USB-C on a 6.1-inch phone.',
    category: 'Phones',
    image: 'https://fdn2.gsmarena.com/vv/bigpic/apple-iphone-15.jpg',
    price: 31999,
    packaging: phone(410),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Xiaomi 14T, 512 GB',
    description: 'Leica-tuned cameras, a 144 Hz display and 67 W charging.',
    category: 'Phones',
    image: 'https://fdn2.gsmarena.com/vv/bigpic/xiaomi-14t.jpg',
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
    image: 'https://dlcdnwebimgs.asus.com/gain/72549656-e4c9-4d7b-94a6-f391d3a9e9e6/',
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
      'https://p2-ofp.static.pub/ShareResource/optimized/pdp/ideapad/ideapad-slim-series/len101i0140/lenovo-ideapad-slim-5a-gen-11-16-amd-pdp-gallery-1.569505cf364158e0.png?width=800&height=800',
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
      'https://store.storeimages.cdn-apple.com/1/as-images.apple.com/is/macbook-air-size-unselect-202601-gallery-1?wid=1200&hei=630&fmt=jpeg&qlt=95&.v=1767638925491',
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
      'https://store.storeimages.cdn-apple.com/1/as-images.apple.com/is/mac-macbook-pro-size-unselect-202601-gallery-1?wid=1200&hei=630&fmt=jpeg&qlt=95&.v=1767812220981',
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
      'https://www.hp.com/wcsstore/hpusstore/Treatment/images/CanopyR-GlacierSilver-Front-573x430.png?imdensity=1',
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
      'https://i.dell.com/is/image/DellContent/content/dam/ss2/product-images/dell-client-products/notebooks/inspiron-notebooks/14-2-in-1-7440-intel/media-gallery/notebook-inspiron-14-7440-t-ice-blue-gallery-4.psd?fmt=png-alpha&pscan=auto&scl=1&hei=402&wid=575',
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
      'https://images.ctfassets.net/jy9s7k22hbg4/4opGrJ3SCBlTO7KGkZt4EU/e0cfbb6b7d602c1f97a6b3925d091a87/Surface_Laptop_1400_13-8inch_jade_CMB.jpg',
    price: 52999,
    packaging: laptop(2400),
    targetStock: 2,
    active: true,
  },
  {
    title: 'ASUS ROG Zephyrus G14, RTX 4060',
    description: 'A 14-inch 3K OLED at 120 Hz, Ryzen 9 and GeForce RTX 4060 in a 1.5 kg chassis.',
    category: 'Laptops',
    image: 'https://dlcdnwebimgs.asus.com/gain/E0275281-F18B-42C3-A025-3331C35A888F',
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
      'https://p3-ofp.static.pub/ShareResource/optimized/pdp/thinkpad/thinkpad-e-series/len101t0143/lenovo-thinkpad-e14-gen-7-14-intel-pdp-gallery-1.png?width=800&height=800',
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
      'https://d1ncau8tqf99kp.cloudfront.net/converted/103364_original_local_1200x1050_v3_converted.webp',
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
      'https://store.storeimages.cdn-apple.com/1/as-images.apple.com/is/MTJV3?wid=1144&hei=1144&fmt=jpeg&qlt=90',
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
      'https://www.jbl.com/dw/image/v2/BFND_PRD/on/demandware.static/-/Sites-masterCatalog_Harman/default/dw561380a8/1_JBL_FLIP6_HERO_BLUE_29399_x1.png?sw=800&sh=800',
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
      'https://images.samsung.com/is/image/samsung/p6pim/uk/2407/gallery/uk-galaxy-buds3-pro-r630-sm-r630nzaaeua-542217308?$1164_776_PNG$',
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
      'https://i02.appmifile.com/964_operatorx_operatorx_opx/10/01/2025/659aea3cbf8efea894d759e3a36cb8b1.png',
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
      'https://images.ctfassets.net/javen7msabdh/Fttl4DrU7xxpEqrPBRiRB/58175527eaf6b1c3a114bfd04e5bbc9b/emberton-iii-blackandbrass-front-desktop.jpeg',
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
      'https://assets.bosecreative.com/transform/dbd0f72b-d68f-4bef-9d38-88132ffa0048/QCUHII26-BoseBlack_SF_PDP_E-Comm_Gallery_1_1500x1120?format=webp&quality=90&io=width:800,height:600,transform:fit',
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
    image: 'https://us.sennheiser-hearing.com/cdn/shop/files/MOMENTUM_4_Black.jpg?v=1759511980',
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
      'https://www.jbl.com/dw/image/v2/BFND_PRD/on/demandware.static/-/Sites-masterCatalog_Harman/default/dwc23d813b/01.JBL_Tune_520BT_ProductImage_Hero_Black.png?sw=800&sh=800',
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
      'https://media.sonos.com/images/znqtjj88/production/c730c924a2d9fe4d3a3b9b9cb7432b7afd0ab392-2000x2000.png?w=800',
    price: 11999,
    packaging: audio(2600),
    targetStock: 3,
    active: true,
  },
  {
    title: 'Anker Soundcore Liberty 4 NC',
    description: 'Adaptive noise cancelling earbuds with up to 50 hours of playtime and LDAC.',
    category: 'Audio',
    image: 'https://uk.soundcore.com/cdn/shop/files/3947Z11-Photoroom.png?v=1756458234',
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
      'https://cdn.shopify.com/s/files/1/0493/9834/9974/products/A1289011-Anker_737_Power_Bank_PowerCore_24K_1.png?v=1775967275',
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
      'https://cdn.shopify.com/s/files/1/0555/8432/5709/files/Baseus_EnerFill_FE22_GaN_Fast_Charger_2C_U_70W_US_1.jpg?v=1781171590',
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
      'https://cdn.shopify.com/s/files/1/0785/4181/6027/files/b81dd756173ef421d96d0ecc0069022f.png?v=1762322048',
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
      'https://store.storeimages.cdn-apple.com/1/as-images.apple.com/is/MX6X3?wid=1144&hei=1144&fmt=jpeg&qlt=90',
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
      'https://resource.logitech.com/w_800,h_686,ar_7:6,c_pad,q_auto,f_auto,dpr_1.0/d_transparent.gif/content/dam/logitech/en/products/mice/mx-master-3s/2025-update/mx-master-3s-bluetooth-edition-top-view-black-new-1.png',
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
      'https://resource.logitech.com/w_800,h_686,ar_7:6,c_pad,q_auto,f_auto,dpr_1.0/d_transparent.gif/content/dam/logitech/en/products/keyboards/mx-keys-s/migration-assets-for-delorean-2025/gallery/mx-keys-s-top-view-black-us.png',
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
      'https://images.samsung.com/is/image/samsung/p6pim/uk/ep-t4511xbeggb/gallery/uk-45w-power-adapter-ep-t4511-ep-t4511xbeggb-thumb-541439950?$650_519_PNG$',
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
      'https://cdn.shopify.com/s/files/1/0808/0067/files/title_web_iphone_16_pro_max_6.9__ultra_hybrid_cc_01.jpg?v=1724691865',
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
      'https://i02.appmifile.com/828_operatorx_operatorx_opx/26/09/2024/e4477095ab705aced7ac8236670d4fde.png',
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
      'https://www.sandisk.com/content/dam/store/en-us/assets/products/usb-flash-drives/extreme-usb-3-2-ssd/gallery/extreme-usb-3-2-ssd-front.png',
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
      'https://static.tp-link.com/upload/image-line/Overview_Archer_AX55_01_large_20210816012555f.jpg',
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
      'https://www.belkin.com/dw/image/v2/BGBH_PRD/on/demandware.static/-/Sites-master-product-catalog-blk/default/dw83520787/images/hi-res/1/33f75aaa4a5a0448_WIZ023dqBK-BoostCharge-Pro-3in1Stand-webgg-hero-01__lz__en_US.jpg?sw=800&sh=800&sm=fit&sfrm=png',
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
